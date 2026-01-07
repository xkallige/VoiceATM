package com.example.voiceatm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var micButton: ImageView
    private lateinit var glowRing: View
    private lateinit var recognitionIntent: Intent
    private val handler = Handler(Looper.getMainLooper())
    private val database = FirebaseDatabase.getInstance().reference

    private var currentState = State.IDLE
    private var temporaryUserData = mutableMapOf<String, Any>()
    private var loggedInUsername: String? = null
    private var pinAttempts = 0

    companion object {
        private const val RECORD_AUDIO_PERMISSION_CODE = 1
    }

    enum class State {
        IDLE,
        WELCOME,
        REGISTRATION_NAME,
        REGISTRATION_CARD,
        REGISTRATION_PIN,
        REGISTRATION_USERNAME,
        LOGIN_USERNAME,
        MAIN_MENU,
        WITHDRAW_AMOUNT,
        WITHDRAW_PIN_CONFIRMATION,
        CHECK_BALANCE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        micButton = findViewById(R.id.micButton)
        glowRing = findViewById(R.id.glowRing)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        } else {
            initializeComponents()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeComponents()
            } else {
                speak("Permission to record audio was not granted. The app cannot function without it. Please grant the permission and restart the app.", "permission_denied")
            }
        }
    }

    private fun initializeComponents() {
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()

        micButton.setOnClickListener {
            if (::speechRecognizer.isInitialized) {
                startListening()
            }
        }

        handler.postDelayed({
            startWelcomeFlow()
        }, 2000)
    }

    private fun startWelcomeFlow() {
        currentState = State.WELCOME
        speak("Welcome to the Voice ATM. Say 1 to register, Say 2 to login, or Say 3 to exit.", "welcome_prompt")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (utteranceId == "exit_utterance") {
                            finish()
                        } else if (currentState != State.IDLE) {
                            startListening()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    if (utteranceId == "exit_utterance") {
                        runOnUiThread {
                            finish()
                        }
                    }
                }
            })
        } else {
            // TTS initialization failed
        }
    }

    private fun speak(text: String, utteranceId: String) {
        runOnUiThread {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available on this device.", "sr_not_available")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-NG") // Nigerian English
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread {
                    glowRing.visibility = View.VISIBLE
                    val glowPulse = AnimationUtils.loadAnimation(this@MainActivity, R.anim.glow_pulse)
                    glowRing.startAnimation(glowPulse)
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                 runOnUiThread {
                    glowRing.clearAnimation()
                    glowRing.visibility = View.INVISIBLE
                }
            }

            override fun onError(error: Int) {
                runOnUiThread {
                    glowRing.clearAnimation()
                    glowRing.visibility = View.INVISIBLE
                }
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    handleTimeout()
                } else {
                     speak("An error occurred during speech recognition. Please try again.", "sr_error")
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val spokenText = matches[0]
                    processVoiceCommand(spokenText)
                } else {
                    handleTimeout()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.startListening(recognitionIntent)
        }
    }

    private fun processVoiceCommand(command: String) {
        val lowerCaseCommand = command.lowercase(Locale.ROOT)
        when (currentState) {
            State.WELCOME -> handleWelcome(lowerCaseCommand)
            State.REGISTRATION_NAME -> handleRegistrationName(command) // Keep original case for names
            State.REGISTRATION_CARD -> handleRegistrationCard(lowerCaseCommand)
            State.REGISTRATION_PIN -> handleRegistrationPin(lowerCaseCommand)
            State.REGISTRATION_USERNAME -> handleRegistrationUsername(command)
            State.LOGIN_USERNAME -> handleLogin(command)
            State.MAIN_MENU -> handleMainMenu(lowerCaseCommand)
            State.WITHDRAW_AMOUNT -> handleWithdrawAmount(lowerCaseCommand)
            State.WITHDRAW_PIN_CONFIRMATION -> handleWithdrawPin(lowerCaseCommand)
            State.CHECK_BALANCE -> handleCheckBalanceResponse(lowerCaseCommand)
            else -> {}
        }
    }

    private fun handleTimeout() {
        val prompt = when (currentState) {
            State.WELCOME -> "We did not hear anything. Please say 1, 2, or 3."
            State.REGISTRATION_NAME -> "We did not hear anything. Please say your full name to continue."
            State.REGISTRATION_CARD -> "We did not hear anything. Please say your twelve-digit card number to continue."
            State.REGISTRATION_PIN -> "We did not hear anything. Please say your four-digit PIN to continue."
            State.REGISTRATION_USERNAME -> "We did not hear anything. Please say your username to continue."
            State.LOGIN_USERNAME -> "We did not hear anything. Please say your username to login."
            State.MAIN_MENU -> "We did not hear anything. Please say 1, 2, or 3."
            State.WITHDRAW_AMOUNT -> "We did not hear anything. Please say the amount you want to withdraw or say cancel."
            State.WITHDRAW_PIN_CONFIRMATION -> "We did not hear anything. Please say your PIN to confirm withdrawal."
            State.CHECK_BALANCE -> "We did not hear anything. Say yes to perform another transaction or say no to exit."
            else -> null
        }
        prompt?.let { speak(it, "timeout_prompt") }
    }

    private fun handleWelcome(command: String) {
        when {
            command.contains("1") || command.contains("one") || command.contains("wan") -> {
                currentState = State.REGISTRATION_NAME
                speak("Please say your full name.", "reg_name_prompt")
            }
            command.contains("2") || command.contains("two") || command.contains("too") -> {
                currentState = State.LOGIN_USERNAME
                speak("Please say your username to login.", "login_prompt")
            }
            command.contains("3") || command.contains("three") || command.contains("tree") -> {
                exitApp()
            }
            else -> {
                speak("Invalid option. Please say 1 to register, 2 to login, or 3 to exit.", "welcome_invalid")
            }
        }
    }

    private fun convertSpokenNumbersToDigits(spokenText: String): String {
        val numberWords = mapOf(
            "zero" to "0", "oh" to "0",
            "one" to "1", "wan" to "1",
            "two" to "2", "too" to "2",
            "three" to "3", "tree" to "3",
            "four" to "4", "fo" to "4",
            "five" to "5",
            "six" to "6",
            "seven" to "7",
            "eight" to "8",
            "nine" to "9"
        )
        return spokenText.split(Regex("\\s+"))
            .mapNotNull { word -> numberWords[word] ?: if (word.all { it.isDigit() }) word else null }
            .joinToString("")
    }

    private fun handleRegistrationName(name: String) {
        temporaryUserData["fullName"] = name
        currentState = State.REGISTRATION_CARD
        speak("Please say your twelve-digit card number.", "reg_card_prompt")
    }

    private fun handleRegistrationCard(command: String) {
        val cardNumber = convertSpokenNumbersToDigits(command)
        if (cardNumber.length == 12) {
            temporaryUserData["cardNumber"] = cardNumber
            currentState = State.REGISTRATION_PIN
            speak("Please say your four-digit PIN.", "reg_pin_prompt")
        } else {
            speak("Invalid card number. Please say your twelve-digit card number again.", "reg_card_invalid")
        }
    }

    private fun handleRegistrationPin(command: String) {
        val pin = convertSpokenNumbersToDigits(command)
        if (pin.length == 4) {
            temporaryUserData["pin"] = pin
            currentState = State.REGISTRATION_USERNAME
            speak("Please say your username.", "reg_username_prompt")
        } else {
            speak("Invalid PIN. Please say your four-digit PIN again.", "reg_pin_invalid")
        }
    }

    private fun handleRegistrationUsername(username: String) {
        temporaryUserData["username"] = username
        temporaryUserData["balance"] = 100000.0
        database.child("users").child(username).setValue(temporaryUserData)
            .addOnSuccessListener {
                speak("Registration successful. Please say your username to login.", "reg_success")
                currentState = State.LOGIN_USERNAME
                temporaryUserData.clear()
            }
            .addOnFailureListener {
                speak("Registration failed. Please try again later.", "reg_fail")
                startWelcomeFlow()
            }
    }

    private fun handleLogin(username: String) {
        database.child("users").child(username).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    loggedInUsername = username
                    speak("Welcome, $username.", "login_success")
                    showMainMenu()
                } else {
                    speak("Incorrect username. Please repeat your username.", "login_incorrect")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                speak("Database error. Please try again later.", "db_error")
                startWelcomeFlow()
            }
        })
    }

    private fun showMainMenu() {
        currentState = State.MAIN_MENU
        speak("Please select a transaction. Say 1 to withdraw, 2 to check balance, or 3 to exit.", "main_menu_prompt")
    }

    private fun handleMainMenu(command: String) {
        when {
            command.contains("1") || command.contains("one") || command.contains("wan") || command.contains("withdraw") || command.contains("witdraw") -> {
                currentState = State.WITHDRAW_AMOUNT
                speak("Please say the amount you want to withdraw.", "withdraw_amount_prompt")
            }
            command.contains("2") || command.contains("two") || command.contains("too") || command.contains("balance") -> {
                checkBalance()
            }
            command.contains("3") || command.contains("three") || command.contains("tree") || command.contains("exit") -> {
                exitApp()
            }
            else -> {
                speak("Invalid option. Please say 1, 2, or 3.", "main_menu_invalid")
            }
        }
    }

    private fun handleWithdrawAmount(amountStr: String) {
        if (amountStr.contains("cancel")) {
            showMainMenu()
            return
        }
        val amount = parseAmount(amountStr)
        if (amount != null && amount > 0) {
            temporaryUserData["withdrawAmount"] = amount
            currentState = State.WITHDRAW_PIN_CONFIRMATION
            speak("Please say your PIN to confirm withdrawal.", "withdraw_pin_prompt")
        } else {
            speak("Invalid amount. Please say a numeric amount, for example 'five thousand' or '5000'. You can also say 'cancel' to go back.", "withdraw_amount_invalid")
        }
    }

    private fun parseAmount(amountStr: String): Double? {
        val lowerAmountStr = amountStr.lowercase(Locale.ROOT)

        if (lowerAmountStr.contains("thousand")) {
            val parts = lowerAmountStr.split("thousand")
            if (parts.isNotEmpty()) {
                val numberPart = parts[0].trim()
                var multiplier: Double? = null

                val asNumber = numberPart.toDoubleOrNull()
                if (asNumber != null) {
                    multiplier = asNumber
                } else {
                    val wordMap = mapOf(
                        "one" to 1.0, "wan" to 1.0,
                        "two" to 2.0, "too" to 2.0,
                        "three" to 3.0, "tree" to 3.0,
                        "five" to 5.0,
                        "ten" to 10.0,
                        "twenty" to 20.0,
                        "fifty" to 50.0
                    )
                    val cleanedNumberPart = numberPart.replace("-", "").replace(" ", "")
                    if(wordMap.containsKey(cleanedNumberPart)) {
                        multiplier = wordMap[cleanedNumberPart]
                    }
                }

                if (multiplier != null) {
                    return multiplier * 1000.0
                }
            }
        }

        val numericRegex = Regex("\\d+")
        val numericMatch = numericRegex.find(amountStr)
        if (numericMatch != null) {
            return numericMatch.value.toDoubleOrNull()
        }

        val cleanedAmount = amountStr.lowercase(Locale.ROOT)
            .replace("naira", "")
            .replace(",", "")
            .replace(" ", "")
            .trim()

        val simpleAmountMap = mapOf(
            "onethousand" to 1000.0,
            "twothousand" to 2000.0,
            "fivethousand" to 5000.0,
            "tenthousand" to 10000.0,
            "twentythousand" to 20000.0,
            "fiftythousand" to 50000.0,
            "onehundredthousand" to 100000.0,
            "one" to 1.0,
            "two" to 2.0,
            "five" to 5.0,
            "ten" to 10.0,
            "twenty" to 20.0,
            "fifty" to 50.0,
            "hundred" to 100.0
        )
        return simpleAmountMap[cleanedAmount]
    }

    private fun handleWithdrawPin(command: String) {
        val pin = convertSpokenNumbersToDigits(command)
        loggedInUsername?.let { username ->
            database.child("users").child(username).child("pin").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val correctPin = snapshot.getValue(String::class.java)
                    if (correctPin == pin) {
                        pinAttempts = 0
                        processWithdrawal()
                    } else {
                        pinAttempts++
                        if (pinAttempts >= 3) {
                            speak("Three incorrect attempts. Your card has been blocked. Please contact your bank.", "pin_blocked")
                            exitApp()
                        } else {
                            speak("Incorrect PIN. Please try again.", "withdraw_pin_incorrect")
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    speak("Database error. Please try again later.", "db_error")
                }
            })
        }
    }

    private fun processWithdrawal() {
        loggedInUsername?.let { username ->
            val withdrawAmount = temporaryUserData["withdrawAmount"] as? Double ?: 0.0
            val userRef = database.child("users").child(username)
            userRef.child("balance").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val currentBalance = snapshot.getValue(Double::class.java) ?: 0.0
                    if (currentBalance >= withdrawAmount) {
                        val newBalance = currentBalance - withdrawAmount
                        userRef.child("balance").setValue(newBalance)
                        currentState = State.MAIN_MENU
                        speak("Withdrawal of $withdrawAmount naira was successful. Please select another transaction. Say 1 to withdraw, 2 to check balance, or 3 to exit.", "withdraw_success_and_next_prompt")
                    } else {
                        speak("Transaction failed. Insufficient funds. Please say a new amount or say cancel to return to the main menu.", "insufficient_funds")
                        currentState = State.WITHDRAW_AMOUNT
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    speak("Database error. Please try again later.", "db_error")
                }
            })
        }
    }

    private fun checkBalance() {
        loggedInUsername?.let { username ->
            database.child("users").child(username).child("balance").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val balance = snapshot.getValue(Double::class.java) ?: 0.0
                    currentState = State.CHECK_BALANCE
                    speak("Your current balance is $balance naira. Say yes to perform another transaction or say no to exit.", "check_balance_result")
                }
                override fun onCancelled(error: DatabaseError) {
                    speak("Database error. Please try again later.", "db_error")
                }
            })
        }
    }

    private fun handleCheckBalanceResponse(command: String) {
        when {
            command.contains("yes") -> showMainMenu()
            command.contains("no") -> exitApp()
            else -> speak("Invalid option. Say yes or no.", "check_balance_invalid")
        }
    }

    private fun exitApp() {
        currentState = State.IDLE
        speak("Thank you for using the Voice ATM.", "exit_utterance")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        handler.removeCallbacksAndMessages(null)
    }
}
