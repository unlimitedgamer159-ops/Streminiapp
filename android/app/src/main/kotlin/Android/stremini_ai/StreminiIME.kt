package Android.stremini_ai

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class StreminiIME : InputMethodService() {

    companion object {
        private const val TAG = "StreminiIME"
        private const val BASE_URL = "https://ai-keyboard-backend.vishwajeetadkine705.workers.dev" // Ensure this matches your worker
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var audioManager: AudioManager

    // Network Client (Optimized for Speed)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS) // Fail fast if network is bad
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Animation Helpers (Pre-allocated for performance)
    private val pressInterpolator = DecelerateInterpolator()
    private val releaseInterpolator = AccelerateDecelerateInterpolator()
    private val handler = Handler(Looper.getMainLooper())

    // State
    private var isShiftOn = false
    private val letterKeyViews = mutableListOf<TextView>()
    private var shiftKeyView: View? = null
    private var currentAppContext = "general"

    // Backspace Repeater
    private var isBackspacePressed = false
    private val backspaceRunnable = object : Runnable {
        override fun run() {
            if (isBackspacePressed) {
                handleBackspace()
                handler.postDelayed(this, 50) // 50ms = 20 chars/sec deletion speed
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_layout, null)
        setupKeyboardInteractions(view)
        return view
    }

    private fun setupKeyboardInteractions(view: View) {
        letterKeyViews.clear()
        shiftKeyView = view.findViewById(R.id.key_shift)

        // 1. Map Keys to Characters
        val keyMap = mapOf(
            R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e", R.id.key_r to "r", R.id.key_t to "t",
            R.id.key_y to "y", R.id.key_u to "u", R.id.key_i to "i", R.id.key_o to "o", R.id.key_p to "p",
            R.id.key_a to "a", R.id.key_s to "s", R.id.key_d to "d", R.id.key_f to "f", R.id.key_g to "g",
            R.id.key_h to "h", R.id.key_j to "j", R.id.key_k to "k", R.id.key_l to "l",
            R.id.key_z to "z", R.id.key_x to "x", R.id.key_c to "c", R.id.key_v to "v", R.id.key_b to "b",
            R.id.key_n to "n", R.id.key_m to "m",
            R.id.key_1 to "1", R.id.key_2 to "2", R.id.key_3 to "3", R.id.key_4 to "4", R.id.key_5 to "5",
            R.id.key_6 to "6", R.id.key_7 to "7", R.id.key_8 to "8", R.id.key_9 to "9", R.id.key_0 to "0",
            R.id.key_dot to ".", R.id.key_comma to ","
        )

        // 2. Attach High-Performance Listeners
        keyMap.forEach { (id, char) ->
            val keyView = view.findViewById<View>(id)
            if (keyView is TextView && char.length == 1 && char[0].isLetter()) {
                letterKeyViews.add(keyView)
            }
            keyView?.setOnTouchListener(createKeyTouchListener(char))
        }

        // Space
        view.findViewById<View>(R.id.key_space)?.setOnTouchListener(createKeyTouchListener(" "))

        // Backspace (Hold to delete)
        view.findViewById<View>(R.id.key_backspace)?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    feedback(v)
                    animateKey(v, true)
                    isBackspacePressed = true
                    handleBackspace()
                    handler.postDelayed(backspaceRunnable, 400) // Wait 400ms before repeating
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    animateKey(v, false)
                    isBackspacePressed = false
                    handler.removeCallbacks(backspaceRunnable)
                }
            }
            true
        }

        // Enter Key
        view.findViewById<View>(R.id.key_enter)?.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                feedback(v)
                animateKey(v, true)
            } else if (event.action == MotionEvent.ACTION_UP) {
                animateKey(v, false)
                handleEnterKey()
            }
            true
        }

        // Shift Key
        shiftKeyView?.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                feedback(v)
                isShiftOn = !isShiftOn
                updateShiftState()
            }
            true // Consume event
        }

        // AI Actions
        setupAiAction(view, R.id.action_improve, "correct")
        setupAiAction(view, R.id.action_complete, "complete")
        setupAiAction(view, R.id.action_tone, "tone")
    }

    // --- Performance Touch Listener ---
    private fun createKeyTouchListener(text: String): View.OnTouchListener {
        return View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Instant Feedback
                    feedback(v)
                    animateKey(v, true)
                }
                MotionEvent.ACTION_UP -> {
                    // Commit on release (standard behavior)
                    animateKey(v, false)
                    commitText(text)
                }
                MotionEvent.ACTION_CANCEL -> {
                    animateKey(v, false)
                }
            }
            true
        }
    }

    // --- Core Logic ---

    private fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        val output = if (isShiftOn && text.length == 1) text.uppercase() else text
        
        ic.commitText(output, 1)

        // Auto-turn off shift after one char
        if (isShiftOn) {
            isShiftOn = false
            updateShiftState()
        }
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun handleEnterKey() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return

        // 1. Check for Multi-Line (Standard Enter)
        val isMultiLine = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (isMultiLine) {
            ic.commitText("\n", 1)
            return
        }

        // 2. Perform Action (Go, Search, Send)
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(action)
        } else {
            // Fallback
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    // --- AI Feature Logic ---

    private fun handleAiAction(actionType: String) {
        val originalText = getCurrentText()
        if (originalText.isBlank()) return

        // Lightweight feedback
        Toast.makeText(this, "Thinking...", Toast.LENGTH_SHORT).show()

        serviceScope.launch(Dispatchers.IO) {
            try {
                // Prepare Request
                val json = JSONObject().apply {
                    put("text", originalText)
                    put("appContext", currentAppContext)
                    // If backend supports "complete_only_new" flag, add it here.
                    // Otherwise we handle deduplication locally.
                }

                val endpoint = when(actionType) {
                    "complete" -> "complete"
                    "tone" -> "tone"
                    else -> "correct"
                }

                val request = Request.Builder()
                    .url("$BASE_URL/keyboard/$endpoint")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
                    val resultJson = JSONObject(responseBody)
                    
                    val resultText = when (actionType) {
                        "complete" -> resultJson.optString("completion")
                        "tone" -> resultJson.optString("rewritten")
                        else -> resultJson.optString("corrected")
                    }

                    if (resultText.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (actionType == "complete") {
                                smartAppend(originalText, resultText)
                            } else {
                                replaceFullText(resultText)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Smartly appends text, preventing duplication.
     * Example: Input="Hello wor", AI="Hello world" -> Appends "ld"
     * Example: Input="Hello", AI=" world" -> Appends " world"
     */
    private fun smartAppend(currentText: String, aiCompletion: String) {
        val ic = currentInputConnection ?: return
        
        // 1. If AI returned the exact full sentence including input
        if (aiCompletion.startsWith(currentText)) {
            val newPart = aiCompletion.substring(currentText.length)
            ic.commitText(newPart, 1)
            return
        }

        // 2. Overlap detection (Suffix of current matching Prefix of AI)
        // Check overlap of up to 20 characters
        val checkLen = min(currentText.length, 20)
        val suffix = currentText.takeLast(checkLen)
        
        // Find if the AI text starts with any part of the suffix
        // e.g. Suffix="lo world", AI="world is big" -> Overlap "world"
        var overlapIndex = -1
        for (i in 0 until checkLen) {
            val sub = suffix.substring(i)
            if (aiCompletion.startsWith(sub)) {
                overlapIndex = sub.length
                break // Found largest overlap
            }
        }

        if (overlapIndex > 0) {
            val newPart = aiCompletion.substring(overlapIndex)
            ic.commitText(newPart, 1)
        } else {
            // No obvious overlap, just append (add space if needed)
            val textToInsert = if (!currentText.endsWith(" ") && !aiCompletion.startsWith(" ")) {
                " $aiCompletion"
            } else {
                aiCompletion
            }
            ic.commitText(textToInsert, 1)
        }
    }

    private fun replaceFullText(newText: String) {
        val ic = currentInputConnection ?: return
        // Select slightly more than needed to ensure we catch everything
        val before = ic.getTextBeforeCursor(5000, 0) ?: ""
        val after = ic.getTextAfterCursor(5000, 0) ?: ""
        ic.deleteSurroundingText(before.length, after.length)
        ic.commitText(newText, 1)
    }

    private fun getCurrentText(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(2000, 0) ?: ""
        val after = ic.getTextAfterCursor(2000, 0) ?: ""
        return "$before$after"
    }

    // --- UX Feedback ---

    private fun feedback(view: View) {
        // 1. Haptic
        view.performHapticFeedback(
            android.view.HapticFeedbackConstants.KEYBOARD_TAP,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        // 2. Sound (Crucial for "Samsung" feel)
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK)
    }

    private fun animateKey(view: View, isPressed: Boolean) {
        val scale = if (isPressed) 0.92f else 1.0f
        val duration = if (isPressed) 40L else 80L // Very fast press, snappy release
        
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(duration)
            .setInterpolator(if (isPressed) pressInterpolator else releaseInterpolator)
            .start()
    }

    private fun updateShiftState() {
        val alpha = if (isShiftOn) 1.0f else 0.5f
        shiftKeyView?.alpha = alpha
        letterKeyViews.forEach { tv ->
            val t = tv.text.toString()
            if (t.isNotEmpty()) tv.text = if (isShiftOn) t.uppercase() else t.lowercase()
        }
    }

    private fun setupAiAction(root: View, id: Int, action: String) {
        root.findViewById<View>(id)?.setOnClickListener { 
            feedback(it)
            handleAiAction(action) 
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Detect App Context
        currentAppContext = when (info?.packageName) {
            "com.whatsapp", "com.facebook.orca" -> "messaging"
            "com.google.android.gm" -> "email"
            else -> "general"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
