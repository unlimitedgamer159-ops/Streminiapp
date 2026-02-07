package com.example.stremini_chatbot

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class StreminiIME : InputMethodService() {

    companion object {
        private const val TAG = "StreminiIME"
        private const val BASE_URL = "https://ai-keyboard-backend.vishwajeetadkine705.workers.dev"
        var isActive = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // State
    private var currentAppContext = "general"
    private var lastComposedText = ""
    private var isShiftOn = false
    private val letterKeyViews = mutableListOf<TextView>()
    private var shiftKeyView: View? = null

    override fun onCreateInputView(): View {
        isActive = true
        val view = layoutInflater.inflate(R.layout.keyboard_layout, null)
        setupKeyboardInteractions(view)
        return view
    }

    private fun setupKeyboardInteractions(view: View) {
        letterKeyViews.clear()
        shiftKeyView = view.findViewById(R.id.key_shift)

        // Map all standard keys
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

        keyMap.forEach { (id, char) ->
            val keyView = view.findViewById<View>(id)
            if (char.length == 1 && char[0].isLetter()) {
                (keyView as? TextView)?.let { letterKeyViews.add(it) }
            }
            keyView?.setOnClickListener {
                playClick(it)
                commitText(char)
            }
        }

        // Special Keys
        view.findViewById<View>(R.id.key_space)?.setOnClickListener {
            playClick(it)
            commitText(" ")
        }

        view.findViewById<View>(R.id.key_backspace)?.setOnClickListener {
            playClick(it)
            handleBackspace()
        }
        
        // Hold backspace to delete continuously
        view.findViewById<View>(R.id.key_backspace)?.setOnLongClickListener {
            // Implementation for continuous delete could go here
             handleBackspace()
             true
        }

        view.findViewById<View>(R.id.key_enter)?.setOnClickListener {
            playClick(it)
            handleEnterKey()
        }

        shiftKeyView?.setOnClickListener {
            playClick(it)
            isShiftOn = !isShiftOn
            updateShiftState()
        }

        view.findViewById<View>(R.id.key_voice)?.setOnClickListener {
             playClick(it)
             Toast.makeText(this, "Voice input coming soon", Toast.LENGTH_SHORT).show()
        }

        // AI Actions
        view.findViewById<View>(R.id.action_improve)?.setOnClickListener {
            playClick(it)
            handleModifyText("improve")
        }

        view.findViewById<View>(R.id.action_complete)?.setOnClickListener {
            playClick(it)
            handleComplete()
        }
        
        view.findViewById<View>(R.id.action_tone)?.setOnClickListener {
             playClick(it)
             handleModifyText("tone")
        }
        
        view.findViewById<View>(R.id.action_undo)?.setOnClickListener {
             playClick(it)
             // Undo implementation would require tracking history
             Toast.makeText(this, "Undo not available yet", Toast.LENGTH_SHORT).show()
        }

        updateShiftState()
    }

    private fun playClick(view: View) {
        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(50).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(50).start()
        }.start()
    }

    private fun commitText(text: String) {
        val updatedText = if (isShiftOn && text.length == 1 && text[0].isLetter()) {
            text.uppercase()
        } else {
            text
        }
        currentInputConnection?.commitText(updatedText, 1)
        if (isShiftOn && text.length == 1 && text[0].isLetter()) {
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

    private fun getCurrentText(): String {
        // Get text before cursor (up to 1000 chars)
        val before = currentInputConnection?.getTextBeforeCursor(1000, 0) ?: ""
        // Get text after cursor (optional, but good for context)
        val after = currentInputConnection?.getTextAfterCursor(1000, 0) ?: ""
        return before.toString() + after.toString()
    }

    // --- AI Features ---

    private fun handleModifyText(action: String) {
        val originalText = getCurrentText()
        if (originalText.isBlank()) {
             Toast.makeText(this, "Type something first!", Toast.LENGTH_SHORT).show()
             return
        }

        // Show loading state (could update chip text)
        Toast.makeText(this, "AI is working...", Toast.LENGTH_SHORT).show()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val requestJson = JSONObject().apply {
                    put("text", originalText)
                    put("action", action)
                    put("context", currentAppContext)
                }
                
                // Endpoint: /keyboard/improve or /keyboard/tone
                // Adjusting endpoint based on action for compatibility with likely backend structure
                val endpoint = if (action == "improve") "correct" else "tone" 

                val request = Request.Builder()
                    .url("$BASE_URL/keyboard/$endpoint")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    // Assuming backend returns 'corrected' or 'result'
                    val newText = json.optString("corrected", json.optString("result", ""))
                    
                    if (newText.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            replaceText(newText)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "AI Error", e)
                withContext(Dispatchers.Main) {
                     Toast.makeText(applicationContext, "AI Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleComplete() {
        val text = getCurrentText()
        if (text.isEmpty()) return
        
        Toast.makeText(this, "Completing...", Toast.LENGTH_SHORT).show()
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                val requestJson = JSONObject().apply {
                    put("text", text)
                }

                val request = Request.Builder()
                    .url("$BASE_URL/keyboard/complete")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val completion = json.optString("completion", "")
                    
                    if (completion.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            commitText(completion) // Append completion
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Complete error", e)
            }
        }
    }

    private fun replaceText(newText: String) {
        val ic = currentInputConnection ?: return
        
        // Delete everything
        val before = ic.getTextBeforeCursor(1000, 0) ?: ""
        val after = ic.getTextAfterCursor(1000, 0) ?: ""
        ic.deleteSurroundingText(before.length, after.length)
        
        // Insert new text
        ic.commitText(newText, 1)
    }

    private fun handleEnterKey() {
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun updateShiftState() {
        shiftKeyView?.alpha = if (isShiftOn) 1f else 0.6f
        letterKeyViews.forEach { keyView ->
            val baseChar = keyView.text.toString()
            if (baseChar.length == 1 && baseChar[0].isLetter()) {
                keyView.text = if (isShiftOn) baseChar.uppercase() else baseChar.lowercase()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isShiftOn = false
        updateShiftState()
        
        currentAppContext = when (info?.packageName) {
            "com.whatsapp", "com.facebook.orca" -> "messaging"
            "com.android.chrome", "com.android.browser" -> "search"
            "com.google.android.gm" -> "email"
            else -> "general"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        isActive = false
    }
}
