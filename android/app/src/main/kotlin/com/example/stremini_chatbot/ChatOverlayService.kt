package com.example.stremini_chatbot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ChatOverlayService : Service(), View.OnTouchListener {

    companion object {
        private const val TAG = "ChatOverlayService"
        private const val CHANNEL_ID = "stremini_overlay"
        private const val MENU_RADIUS = 110f
        private const val BUTTON_SIZE = 60
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var bubbleIcon: ImageView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var menuView: View? = null
    private var menuLayoutParams: WindowManager.LayoutParams? = null
    private var isMenuVisible = false

    private var chatboxView: View? = null
    private var chatboxLayoutParams: WindowManager.LayoutParams? = null
    private var isChatboxVisible = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundService()
        setupOverlay()
    }

    private fun setupOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.chat_bubble_layout, null)
        bubbleIcon = overlayView.findViewById(R.id.bubble_icon)
        bubbleIcon.setOnTouchListener(this)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        windowManager.addView(overlayView, layoutParams)
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()

                if (abs(dx) > 10 || abs(dy) > 10) {
                    isDragging = true
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    
                    if (isMenuVisible && menuView != null) {
                        updateMenuPosition()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    toggleMenu()
                }
                return true
            }
        }
        return false
    }

    private fun toggleMenu() {
        if (isMenuVisible) {
            hideMenu()
        } else {
            showMenu()
        }
    }

    private fun showMenu() {
        if (menuView != null) return
        
        bubbleIcon.animate().rotation(45f).setDuration(200).start()
        
        menuView = LayoutInflater.from(this).inflate(R.layout.radial_menu_layout, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val menuContainerSize = (MENU_RADIUS * 2.5).toInt()
        
        menuLayoutParams = WindowManager.LayoutParams(
            menuContainerSize,
            menuContainerSize,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        
        windowManager.addView(menuView, menuLayoutParams)
        updateMenuPosition()
        setupRadialButtons()
        isMenuVisible = true
        Log.d(TAG, "✅ Menu shown, container size: $menuContainerSize")
    }
    
    private fun updateMenuPosition() {
        if (menuLayoutParams == null || menuView == null) return
        
        val bubbleWidth = overlayView.width
        val bubbleHeight = overlayView.height
        val menuContainerSize = menuLayoutParams!!.width
        
        menuLayoutParams!!.x = layoutParams.x - (menuContainerSize / 2) + (bubbleWidth / 2)
        menuLayoutParams!!.y = layoutParams.y - (menuContainerSize / 2) + (bubbleHeight / 2)
        
        windowManager.updateViewLayout(menuView, menuLayoutParams)
    }
    
    private fun setupRadialButtons() {
        if (menuView == null) return
        
        overlayView.post {
            val menuContainerSize = menuLayoutParams!!.width
            val centerX = menuContainerSize / 2f
            val centerY = menuContainerSize / 2f
            
            val screenWidth = resources.displayMetrics.widthPixels
            val isOnRightSide = (layoutParams.x + (overlayView.width / 2)) > (screenWidth / 2)
            
            val angles = if (isOnRightSide) {
                listOf(90, 126, 162, 198, 234)
            } else {
                listOf(90, 54, 18, -18, -54)
            }
            
            val buttonIds = listOf(
                R.id.btn_refresh,
                R.id.btn_settings,
                R.id.btn_ai,
                R.id.btn_scanner,
                R.id.btn_keyboard
            )

            buttonIds.forEachIndexed { idx, resId ->
                try {
                    val btn = menuView!!.findViewById<ImageView>(resId)
                    val angleRad = Math.toRadians(angles[idx].toDouble())
                    
                    val btnX = centerX + (MENU_RADIUS * cos(angleRad)).toFloat() - (BUTTON_SIZE / 2)
                    val btnY = centerY - (MENU_RADIUS * sin(angleRad)).toFloat() - (BUTTON_SIZE / 2)
                    
                    btn.x = btnX
                    btn.y = btnY
                    btn.alpha = 1f
                    btn.scaleX = 1f
                    btn.scaleY = 1f
                    btn.visibility = View.VISIBLE
                    
                    btn.setOnClickListener {
                        handleMenuItemClick(idx)
                    }
                    
                    Log.d(TAG, "Button $idx set at x=$btnX, y=$btnY, visible=${btn.visibility == View.VISIBLE}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up button $idx", e)
                }
            }
        }
    }
    
    private fun handleMenuItemClick(index: Int) {
        when (index) {
            0 -> Toast.makeText(this, "Refresh", Toast.LENGTH_SHORT).show()
            1 -> Toast.makeText(this, "Settings", Toast.LENGTH_SHORT).show()
            2 -> openChatbox()
            3 -> {
                val intent = Intent(this, ScreenReaderService::class.java)
                intent.action = ScreenReaderService.ACTION_START_SCAN
                startService(intent)
            }
            4 -> Toast.makeText(this, "Keyboard", Toast.LENGTH_SHORT).show()
        }
        hideMenu()
    }

    private fun openChatbox() {
        if (isChatboxVisible) return
        
        chatboxView = LayoutInflater.from(this).inflate(R.layout.floating_chatbot_layout, null)
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        
        chatboxLayoutParams = WindowManager.LayoutParams(
            (screenWidth * 0.85).toInt(),
            (screenHeight * 0.6).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 20
            y = 100
        }
        
        setupChatbox()
        windowManager.addView(chatboxView, chatboxLayoutParams)
        isChatboxVisible = true
    }
    
    private fun setupChatbox() {
        val messagesContainer = chatboxView!!.findViewById<LinearLayout>(R.id.messages_container)
        val inputField = chatboxView!!.findViewById<EditText>(R.id.et_chat_input)
        val sendButton = chatboxView!!.findViewById<ImageView>(R.id.btn_send_message)
        val closeButton = chatboxView!!.findViewById<ImageView>(R.id.btn_close_chat)
        val scrollView = chatboxView!!.findViewById<ScrollView>(R.id.scroll_messages)
        
        // Add welcome message
        addBotMessage(messagesContainer, "Hello! I'm Stremini AI. How can I help you?")
        
        sendButton.setOnClickListener {
            val message = inputField.text.toString().trim()
            if (message.isNotEmpty()) {
                addUserMessage(messagesContainer, message)
                inputField.text.clear()
                
                serviceScope.launch {
                    scrollView.fullScroll(View.FOCUS_DOWN)
                    delay(100)
                    
                    val response = sendMessageToAPI(message)
                    addBotMessage(messagesContainer, response)
                    scrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
        
        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendButton.performClick()
                true
            } else false
        }
        
        closeButton.setOnClickListener {
            closeChatbox()
        }
    }
    
    private fun addUserMessage(container: LinearLayout, message: String) {
        val messageView = LayoutInflater.from(this).inflate(R.layout.message_bubble_user, container, false)
        messageView.findViewById<TextView>(R.id.tv_message).text = message
        container.addView(messageView)
    }
    
    private fun addBotMessage(container: LinearLayout, message: String) {
        val messageView = LayoutInflater.from(this).inflate(R.layout.message_bubble_bot, container, false)
        messageView.findViewById<TextView>(R.id.tv_message).text = message
        container.addView(messageView)
    }
    
    private suspend fun sendMessageToAPI(message: String): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("message", message)
            }.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("https://ai-keyboard-backend.vishwajeetadkine705.workers.dev/chat/message")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext "No response"
            
            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                json.optString("response", json.optString("reply", "I'm here to help!"))
            } else {
                "Sorry, I couldn't process that."
            }
        } catch (e: Exception) {
            Log.e(TAG, "API Error", e)
            "Sorry, an error occurred."
        }
    }
    
    private fun closeChatbox() {
        chatboxView?.let {
            windowManager.removeView(it)
            chatboxView = null
            chatboxLayoutParams = null
            isChatboxVisible = false
        }
    }

    private fun hideMenu() {
        bubbleIcon.animate().rotation(0f).setDuration(200).start()
        menuView?.let {
            windowManager.removeView(it)
            menuView = null
            menuLayoutParams = null
            isMenuVisible = false
            Log.d(TAG, "Menu hidden")
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stremini Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stremini AI")
            .setContentText("Overlay active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        hideMenu()
        closeChatbox()
    }
}
