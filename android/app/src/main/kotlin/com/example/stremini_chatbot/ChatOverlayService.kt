package com.example.stremini_chatbot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ChatOverlayService : Service(), View.OnTouchListener {

    companion object {
        private const val TAG = "ChatOverlayService"
        private const val CHANNEL_ID = "stremini_overlay"
        
        // --- CALIBRATED FOR IMAGE MATCHING ---
        private const val MENU_RADIUS_DP = 95f   // Increased distance so icons don't overlap
        private const val BUTTON_SIZE_DP = 48    // Larger icon size to match the original
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

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

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
            y = 300
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
                    
                    // If dragging while menu is open, move menu too or close it
                    if (isMenuVisible) updateMenuPosition()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // This is a click -> Toggle Menu
                    toggleMenu()
                } else {
                    // Snap to edge logic
                    val screenWidth = resources.displayMetrics.widthPixels
                    layoutParams.x = if (layoutParams.x < screenWidth / 2) 0 else screenWidth - overlayView.width
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    if (isMenuVisible) updateMenuPosition()
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

        // Rotate bubble to indicate open state
        bubbleIcon.animate().rotation(45f).setDuration(200).start()
        
        menuView = FrameLayout(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

        // Large container to prevent clipping icons
        val menuContainerSize = dpToPx((MENU_RADIUS_DP * 2.5).toInt())
        menuLayoutParams = WindowManager.LayoutParams(
            menuContainerSize, menuContainerSize, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        
        windowManager.addView(menuView, menuLayoutParams)
        updateMenuPosition()
        setupRadialButtons()
        isMenuVisible = true
    }
    
    private fun hideMenu() {
        if (!isMenuVisible) return

        // Rotate bubble back to original state
        bubbleIcon.animate().rotation(0f).setDuration(200).start()
        
        menuView?.let {
            windowManager.removeView(it)
            menuView = null
        }
        isMenuVisible = false
    }
    
    private fun updateMenuPosition() {
        if (menuLayoutParams == null || menuView == null) return
        val bubbleWidth = overlayView.width
        val bubbleHeight = overlayView.height
        val menuSize = menuLayoutParams!!.width
        
        // Center the menu container over the bubble
        menuLayoutParams!!.x = layoutParams.x - (menuSize / 2) + (bubbleWidth / 2)
        menuLayoutParams!!.y = layoutParams.y - (menuSize / 2) + (bubbleHeight / 2)
        windowManager.updateViewLayout(menuView, menuLayoutParams)
    }
    
    private fun setupRadialButtons() {
        if (menuView == null) return
        val btnSizePx = dpToPx(BUTTON_SIZE_DP)
        val radiusPx = dpToPx(MENU_RADIUS_DP.toInt())
        
        overlayView.post {
            val centerX = menuLayoutParams!!.width / 2f
            val centerY = menuLayoutParams!!.height / 2f
            val screenWidth = resources.displayMetrics.widthPixels
            val isOnRightSide = (layoutParams.x + (overlayView.width / 2)) > (screenWidth / 2)
            
            // Balanced angles for a perfect side-fan
            val angles = if (isOnRightSide) {
                listOf(110, 145, 180, 215, 250) 
            } else {
                listOf(70, 35, 0, -35, -70) 
            }
            
            val icons = listOf(
                android.R.drawable.ic_dialog_email,
                android.R.drawable.ic_menu_edit,
                android.R.drawable.ic_menu_info_details,
                android.R.drawable.ic_input_get,
                android.R.drawable.ic_lock_idle_lock
            )
            
            val colors = listOf("#23A6E2", "#23A6E2", "#007BFF", "#23A6E2", "#23A6E2")

            icons.forEachIndexed { idx, iconRes ->
                val angleRad = Math.toRadians(angles[idx].toDouble())
                val btnX = centerX + (radiusPx * cos(angleRad)).toFloat() - (btnSizePx / 2)
                val btnY = centerY - (radiusPx * sin(angleRad)).toFloat() - (btnSizePx / 2)
                
                val button = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(btnSizePx, btnSizePx)
                    x = btnX
                    y = btnY
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dpToPx(7), dpToPx(7), dpToPx(7), dpToPx(7))
                    
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(android.graphics.Color.parseColor("#121212"))
                        setStroke(dpToPx(2), android.graphics.Color.parseColor(colors[idx]))
                    }
                    setColorFilter(android.graphics.Color.parseColor(colors[idx]))
                    elevation = dpToPx(6).toFloat()
                    setOnClickListener { handleMenuItemClick(idx) }
                }
                (menuView as FrameLayout).addView(button)
            }
        }
    }
    
    private fun handleMenuItemClick(index: Int) {
        // Close menu after clicking an option
        hideMenu()
        
        when (index) {
            2 -> openChatbox()
            3 -> {
                val intent = Intent(this, ScreenReaderService::class.java)
                intent.action = ScreenReaderService.ACTION_START_SCAN
                startService(intent)
            }
        }
    }

    private fun openChatbox() {
        if (isChatboxVisible) return
        hideMenu() // Ensure menu is closed when opening chat
        chatboxView = LayoutInflater.from(this).inflate(R.layout.floating_chatbot_layout, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        
        chatboxLayoutParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            (resources.displayMetrics.heightPixels * 0.6).toInt(),
            type, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = 20; y = 100 }
        
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
        
        addBotMessage(messagesContainer, "Hello! I'm Stremini AI.")
        
        sendButton.setOnClickListener {
            val message = inputField.text.toString().trim()
            if (message.isNotEmpty()) {
                addUserMessage(messagesContainer, message)
                inputField.text.clear()
                serviceScope.launch {
                    scrollView.fullScroll(View.FOCUS_DOWN)
                    delay(100)
                    addBotMessage(messagesContainer, sendMessageToAPI(message))
                    scrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
        closeButton.setOnClickListener { closeChatbox() }
    }
    
    private fun addUserMessage(container: LinearLayout, message: String) {
        val v = LayoutInflater.from(this).inflate(R.layout.message_bubble_user, container, false)
        v.findViewById<TextView>(R.id.tv_message).text = message
        container.addView(v)
    }
    
    private fun addBotMessage(container: LinearLayout, message: String) {
        val v = LayoutInflater.from(this).inflate(R.layout.message_bubble_bot, container, false)
        v.findViewById<TextView>(R.id.tv_message).text = message
        container.addView(v)
    }
    
    private suspend fun sendMessageToAPI(message: String): String = withContext(Dispatchers.IO) {
        "Response to: $message" 
    }
    
    private fun closeChatbox() {
        chatboxView?.let { windowManager.removeView(it); chatboxView = null; isChatboxVisible = false }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Stremini", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("Stremini").setSmallIcon(R.mipmap.ic_launcher).build()
        startForeground(1, n)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        hideMenu(); closeChatbox()
    }
}
