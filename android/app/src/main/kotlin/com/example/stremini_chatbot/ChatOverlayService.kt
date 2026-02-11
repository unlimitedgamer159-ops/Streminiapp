package com.example.stremini_chatbot

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ChatOverlayService : Service(), View.OnTouchListener {

    companion object {
        const val ACTION_SEND_MESSAGE = "com.example.stremini_chatbot.SEND_MESSAGE"
        const val EXTRA_MESSAGE = "message"
        const val ACTION_SCANNER_START = "com.example.stremini_chatbot.SCANNER_START"
        const val ACTION_SCANNER_STOP = "com.example.stremini_chatbot.SCANNER_STOP"
        val NEON_BLUE: Int = android.graphics.Color.parseColor("#00D9FF")
        val WHITE: Int = android.graphics.Color.parseColor("#FFFFFF")
        
        private const val TAG = "ChatOverlayService"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: WindowManager.LayoutParams

    private var floatingChatView: View? = null
    private var floatingChatParams: WindowManager.LayoutParams? = null
    private var isChatbotVisible = false

    private lateinit var bubbleIcon: ImageView
    private lateinit var menuItems: List<ImageView>
    private var isMenuExpanded = false

    private val activeFeatures = mutableSetOf<Int>()
    private var isScannerActive = false
    private lateinit var inputMethodManager: InputMethodManager

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var hasMoved = false

    private val bubbleSizeDp = 60f
    private val menuItemSizeDp = 50f
    private val radiusDp = 80f

    private var bubbleScreenX = 0
    private var bubbleScreenY = 0

    private var isMenuAnimating = false
    private var windowAnimator: ValueAnimator? = null
    private var isWindowResizing = false
    private var preventPositionUpdates = false
    private var lastDragUpdateTime = 0L
    private var scanningOverlayView: ScanningOverlayView? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SEND_MESSAGE -> {
                    val message = intent.getStringExtra(EXTRA_MESSAGE)
                    if (message != null) {
                        Log.d(TAG, "Received message: $message")
                        // If chat isn't visible, show it so user sees the alert
                        if (!isChatbotVisible) showFloatingChatbot()
                        addMessageToChatbot(message, isUser = false)
                    }
                }
                ACTION_SCANNER_START -> {
                    isScannerActive = true
                    updateMenuItemsColor()
                    Toast.makeText(context, "Screen Detection Started", Toast.LENGTH_SHORT).show()
                }
                ACTION_SCANNER_STOP -> {
                    isScannerActive = false
                    updateMenuItemsColor()
                    Toast.makeText(context, "Screen Detection Stopped", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        
        startForegroundService()
        setupOverlay()
        scanningOverlayView = ScanningOverlayView(this)

        val filter = IntentFilter().apply {
            addAction(ACTION_SEND_MESSAGE)
            addAction(ACTION_SCANNER_START)
            addAction(ACTION_SCANNER_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
        
        Log.d(TAG, "Service setup complete")
    }

    private fun setupOverlay() {
        try {
            overlayView = LayoutInflater.from(this).inflate(R.layout.chat_bubble_layout, null)
            bubbleIcon = overlayView.findViewById(R.id.bubble_icon)

            menuItems = listOf(
                overlayView.findViewById(R.id.btn_refresh),
                overlayView.findViewById(R.id.btn_settings),
                overlayView.findViewById(R.id.btn_ai),
                overlayView.findViewById(R.id.btn_scanner),
                overlayView.findViewById(R.id.btn_keyboard)
            )

            val typeParam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }

            val radiusPx = dpToPx(radiusDp).toFloat()
            val bubbleSizePx = dpToPx(bubbleSizeDp).toFloat()
            val collapsedWindowSizePx = (bubbleSizePx + dpToPx(10f)).toInt()

            params = WindowManager.LayoutParams(
                collapsedWindowSizePx,
                collapsedWindowSizePx,
                typeParam,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            
            val screenHeight = resources.displayMetrics.heightPixels
            bubbleScreenX = 60
            bubbleScreenY = (screenHeight * 0.25).toInt()
            
            val windowHalfSize = collapsedWindowSizePx / 2
            params.x = bubbleScreenX - windowHalfSize
            params.y = bubbleScreenY - windowHalfSize

            bubbleIcon.setOnTouchListener(this)

            menuItems[0].setOnClickListener {
                collapseMenu()
                handleRefresh()
            }
            menuItems[1].setOnClickListener {
                collapseMenu()
                handleSettings()
            }
            menuItems[2].setOnClickListener {
                collapseMenu()
                handleAIChat()
            }
            menuItems[3].setOnClickListener {
                collapseMenu()
                handleScanner()
            }
            menuItems[4].setOnClickListener {
                collapseMenu()
                handleVoiceCommand()
            }

            bubbleIcon.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            bubbleIcon.isClickable = true
            bubbleIcon.isFocusable = true
            
            menuItems.forEach { 
                it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                it.isClickable = true
                it.isFocusable = true
                it.visibility = View.INVISIBLE
            }

            updateMenuItemsColor()
            
            overlayView.background = null
            overlayView.isClickable = false
            overlayView.isFocusable = false
            overlayView.setOnTouchListener { _, _ -> false }
            
            windowManager.addView(overlayView, params)

            (overlayView as? android.view.ViewGroup)?.apply {
                clipToPadding = false
                clipChildren = false
                isMotionEventSplittingEnabled = false
            }

            overlayView.layoutParams = overlayView.layoutParams?.apply {
                width = params.width
                height = params.height
            }
            overlayView.requestLayout()
            
            Log.d(TAG, "Overlay view added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up overlay", e)
        }
    }

    private fun handleAIChat() {
        toggleFeature(menuItems[2].id)
        if (isFeatureActive(menuItems[2].id)) {
            showFloatingChatbot()
        } else {
            hideFloatingChatbot()
        }
    }

    private fun showFloatingChatbot() {
        if (isChatbotVisible) return

        try {
            floatingChatView = LayoutInflater.from(this).inflate(R.layout.floating_chatbot_layout, null)

            val typeParam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }

            floatingChatParams = WindowManager.LayoutParams(
                dpToPx(300f),
                dpToPx(400f),
                typeParam,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )

            floatingChatParams?.gravity = Gravity.BOTTOM or Gravity.END
            floatingChatParams?.x = dpToPx(20f)
            floatingChatParams?.y = dpToPx(100f)

            floatingChatView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            setupFloatingChatListeners()

            windowManager.addView(floatingChatView, floatingChatParams)
            isChatbotVisible = true

            addMessageToChatbot("Hello! I'm Stremini AI. How can I help you?", isUser = false)
            
            Log.d(TAG, "Floating chatbot shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing chatbot", e)
        }
    }

    private fun setupFloatingChatListeners() {
        floatingChatView?.let { view ->
            val header = view.findViewById<LinearLayout>(R.id.chat_header)
            var chatInitialX = 0
            var chatInitialY = 0
            var chatInitialTouchX = 0f
            var chatInitialTouchY = 0f
            var chatIsDragging = false

            header?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        chatInitialTouchX = event.rawX
                        chatInitialTouchY = event.rawY
                        chatInitialX = floatingChatParams?.x ?: 0
                        chatInitialY = floatingChatParams?.y ?: 0
                        chatIsDragging = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (chatIsDragging && floatingChatParams != null) {
                            val deltaX = (event.rawX - chatInitialTouchX).toInt()
                            val deltaY = (event.rawY - chatInitialTouchY).toInt()
                            floatingChatParams?.x = chatInitialX - deltaX
                            floatingChatParams?.y = chatInitialY - deltaY
                            windowManager.updateViewLayout(floatingChatView!!, floatingChatParams!!)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        chatIsDragging = false
                    }
                }
                true
            }

            view.findViewById<ImageView>(R.id.btn_close_chat)?.setOnClickListener {
                hideFloatingChatbot()
                toggleFeature(menuItems[2].id)
            }

            view.findViewById<ImageView>(R.id.btn_send_message)?.setOnClickListener {
                val input = view.findViewById<EditText>(R.id.et_chat_input)
                val message = input?.text?.toString()?.trim()
                if (!message.isNullOrEmpty()) {
                    addMessageToChatbot(message, isUser = true)
                    input.text?.clear()
                    sendMessageToAPI(message)
                }
            }

            view.findViewById<ImageView>(R.id.btn_voice_input)?.setOnClickListener {
                Toast.makeText(this, "Voice input coming soon", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendMessageToAPI(userMessage: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val requestJson = JSONObject().apply {
                    put("message", userMessage)
                }

                val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://ai-keyboard-backend.vishwajeetadkine705.workers.dev/chat/message")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val json = JSONObject(responseBody)
                    val reply = json.optString("reply",
                        json.optString("response",
                            json.optString("message", "No response from AI")))
                    withContext(Dispatchers.Main) {
                        addMessageToChatbot(reply, isUser = false)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        addMessageToChatbot("❌ Server error: ${response.code}", isUser = false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addMessageToChatbot("⚠️ Network error: ${e.message}", isUser = false)
                }
            }
        }
    }

    private fun addMessageToChatbot(message: String, isUser: Boolean) {
        floatingChatView?.let { view ->
            val messagesContainer = view.findViewById<LinearLayout>(R.id.messages_container)
            val messageView = LayoutInflater.from(this).inflate(
                if (isUser) R.layout.message_bubble_user else R.layout.message_bubble_bot,
                messagesContainer,
                false
            )
            messageView.findViewById<TextView>(R.id.tv_message)?.text = message
            messagesContainer?.addView(messageView)
            view.findViewById<ScrollView>(R.id.scroll_messages)?.post {
                view.findViewById<ScrollView>(R.id.scroll_messages)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun hideFloatingChatbot() {
        if (!isChatbotVisible) return
        floatingChatView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing chatbot view", e)
            }
            floatingChatView = null
            floatingChatParams = null
            isChatbotVisible = false
        }
    }

    private fun handleScanner() {
        isScannerActive = true
        updateMenuItemsColor()

        val intent = Intent(this, ScreenReaderService::class.java)
        intent.action = ScreenReaderService.ACTION_PERFORM_SINGLE_SCAN
        startService(intent)
        Toast.makeText(this, "Scanning current screen...", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Single screen scan triggered")

        overlayView.postDelayed({
            isScannerActive = false
            updateMenuItemsColor()
        }, 3500)
    }

    private fun handleVoiceCommand() {
        toggleFeature(menuItems[4].id)
        
        if (isFeatureActive(menuItems[4].id)) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "Open Settings to enable AI Keyboard", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try {
                    inputMethodManager.showInputMethodPicker()
                    Toast.makeText(this, "Select AI Keyboard from the list", Toast.LENGTH_SHORT).show()
                } catch (ex: Exception) {
                    Toast.makeText(this, "AI Keyboard feature enabled", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "AI Keyboard Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSettings() {
        openMainApp()
        Toast.makeText(this, "Opening Stremini...", Toast.LENGTH_SHORT).show()
    }

    private fun handleRefresh() {
        activeFeatures.clear()
        
        if (isScannerActive) {
            isScannerActive = false
            val intent = Intent(this, ScreenReaderService::class.java)
            intent.action = ScreenReaderService.ACTION_STOP_SCAN
            startService(intent)
        }
        
        hideFloatingChatbot()
        
        updateMenuItemsColor()
        Toast.makeText(this, "Refresh Complete - All features reset", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFeature(featureId: Int) {
        if (activeFeatures.contains(featureId)) {
            activeFeatures.remove(featureId)
        } else {
            activeFeatures.add(featureId)
        }
        updateMenuItemsColor()
    }

    private fun isFeatureActive(featureId: Int): Boolean {
        return activeFeatures.contains(featureId)
    }

    private fun updateMenuItemsColor() {
        menuItems.forEach { item ->
            if (activeFeatures.contains(item.id) ||
                (item.id == menuItems[3].id && isScannerActive)) {
                val layers = arrayOf(
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(android.graphics.Color.BLACK)
                    },
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(android.graphics.Color.parseColor("#23A6E2"))
                        alpha = 200
                    }
                )
                item.background = android.graphics.drawable.LayerDrawable(layers)
                item.setColorFilter(WHITE)
            } else {
                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                drawable.setColor(android.graphics.Color.BLACK)
                item.background = drawable
                item.setColorFilter(WHITE)
            }
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialX = bubbleScreenX
                initialY = bubbleScreenY
                isDragging = false
                hasMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isWindowResizing || preventPositionUpdates) return true
                val now = System.currentTimeMillis()
                if (now - lastDragUpdateTime < 12) return true
                lastDragUpdateTime = now

                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                
                if (abs(dx) > 10 || abs(dy) > 10) {
                    hasMoved = true
                    if (!isMenuExpanded) {
                        isDragging = true

                        bubbleScreenX = initialX + dx
                        bubbleScreenY = initialY + dy

                        val bubbleSizePx = dpToPx(bubbleSizeDp).toFloat()
                        val collapsedWindowSizePx = bubbleSizePx + dpToPx(10f)
                        val windowHalfSize = collapsedWindowSizePx / 2

                        params.x = (bubbleScreenX - windowHalfSize).toInt()
                        params.y = (bubbleScreenY - windowHalfSize).toInt()
                        
                        try {
                            windowManager.updateViewLayout(overlayView, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating layout", e)
                        }
                    } else {
                        if (!isMenuAnimating) collapseMenu()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isWindowResizing || preventPositionUpdates) {
                    isDragging = false
                    hasMoved = false
                    return true
                }

                if (!hasMoved && !isDragging) {
                    if (!isMenuAnimating) toggleMenu()
                } else if (isDragging) {
                    if (isWindowResizing || preventPositionUpdates) {
                        overlayView.postDelayed({ snapToEdge() }, 200)
                    } else {
                        snapToEdge()
                    }
                }
                isDragging = false
                hasMoved = false
                return true
            }
        }
        return false
    }

    private fun toggleMenu() {
        if (isMenuAnimating) return
        if (isMenuExpanded) collapseMenu() else expandMenu()
    }

    private fun expandMenu() {
        if (isMenuAnimating || isMenuExpanded) return
        isMenuExpanded = true
        isMenuAnimating = true

        val radiusPx = dpToPx(radiusDp).toFloat()
        val bubbleSizePx = dpToPx(bubbleSizeDp).toFloat()
        val menuItemSizePx = dpToPx(menuItemSizeDp).toFloat()

        val expandedWindowSizePx = (radiusPx * 2) + bubbleSizePx + dpToPx(20f)
        val collapsedWindowSizePx = bubbleSizePx + dpToPx(10f)

        animateWindowSize(collapsedWindowSizePx.toFloat(), expandedWindowSizePx, 220L) {
            isMenuAnimating = false
        }

        val centerX = expandedWindowSizePx / 2f
        val centerY = expandedWindowSizePx / 2f

        val screenWidth = resources.displayMetrics.widthPixels
        val isOnRightSide = bubbleScreenX > (screenWidth / 2)

        val fixedAngles = if (isOnRightSide) {
            listOf(90.0, 135.0, 180.0, 225.0, 270.0)
        } else {
            listOf(90.0, 45.0, 0.0, -45.0, -90.0)
        }

        overlayView.postDelayed({
            for ((index, view) in menuItems.withIndex()) {
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.translationX = 0f
                view.translationY = 0f

                val angle = fixedAngles[index]
                val rad = Math.toRadians(angle)

                val targetX = centerX + (radiusPx * cos(rad)).toFloat() - (menuItemSizePx / 2)
                val targetY = centerY + (radiusPx * -sin(rad)).toFloat() - (menuItemSizePx / 2)

                val initialCenteredX = centerX - (menuItemSizePx / 2)
                val initialCenteredY = centerY - (menuItemSizePx / 2)

                view.animate()
                    .translationX(targetX - initialCenteredX)
                    .translationY(targetY - initialCenteredY)
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            updateMenuItemsColor()
        }, 160)
    }

    private fun collapseMenu() {
        if (isMenuAnimating || !isMenuExpanded) return
        isMenuExpanded = false
        isMenuAnimating = true

        val radiusPx = dpToPx(radiusDp).toFloat()
        val bubbleSizePx = dpToPx(bubbleSizeDp).toFloat()
        val expandedWindowSizePx = (radiusPx * 2) + bubbleSizePx + dpToPx(20f)
        val collapsedWindowSizePx = bubbleSizePx + dpToPx(10f)

        for (view in menuItems) {
            view.animate()
                .translationX(0f)
                .translationY(0f)
                .alpha(0f)
                .setDuration(150)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { view.visibility = View.INVISIBLE }
                .start()
        }

        overlayView.postDelayed({
            animateWindowSize(expandedWindowSizePx, collapsedWindowSizePx.toFloat(), 200L) {
                isMenuAnimating = false
            }
        }, 120)
    }

    private fun animateWindowSize(fromSize: Float, toSize: Float, duration: Long = 200L, onEnd: (() -> Unit)? = null) {
        windowAnimator?.cancel()
        isWindowResizing = true
        preventPositionUpdates = true

        val fromHalf = fromSize / 2f
        val toHalf = toSize / 2f
        val startX = bubbleScreenX - fromHalf
        val endX = bubbleScreenX - toHalf
        val startY = bubbleScreenY - fromHalf
        val endY = bubbleScreenY - toHalf

        windowAnimator = ValueAnimator.ofFloat(fromSize, toSize).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            
            addUpdateListener { animator ->
                val newSize = animator.animatedValue as Float
                val frac = if (toSize != fromSize) (newSize - fromSize) / (toSize - fromSize) else 1f
                
                params.width = newSize.toInt()
                params.height = newSize.toInt()
                params.x = (startX + (endX - startX) * frac).toInt()
                params.y = (startY + (endY - startY) * frac).toInt()

                try {
                    windowManager.updateViewLayout(overlayView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in animation", e)
                }
            }
            
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    windowAnimator = null
                    isWindowResizing = false
                    preventPositionUpdates = false

                    params.width = toSize.toInt()
                    params.height = toSize.toInt()
                    params.x = (bubbleScreenX - toHalf).toInt()
                    params.y = (bubbleScreenY - toHalf).toInt()
                    
                    try {
                        windowManager.updateViewLayout(overlayView, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in animation end", e)
                    }

                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun snapToEdge() {
        if (isWindowResizing || preventPositionUpdates || isMenuAnimating) {
            overlayView.postDelayed({ snapToEdge() }, 150)
            return
        }

        val bubbleSizePx = dpToPx(bubbleSizeDp).toFloat()
        val screenWidth = resources.displayMetrics.widthPixels
        val collapsedWindowSizePx = bubbleSizePx + dpToPx(10f)
        val windowHalfSize = collapsedWindowSizePx / 2

        val targetBubbleScreenX = if (bubbleScreenX > (screenWidth / 2)) {
            screenWidth - (bubbleSizePx / 2).toInt()
        } else {
            (bubbleSizePx / 2).toInt()
        }

        ValueAnimator.ofInt(bubbleScreenX, targetBubbleScreenX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            
            addUpdateListener { animator ->
                bubbleScreenX = animator.animatedValue as Int
                params.x = (bubbleScreenX - windowHalfSize).toInt()
                
                try {
                    windowManager.updateViewLayout(overlayView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Error snapping to edge", e)
                }
            }
            start()
        }
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun startForegroundService() {
        val channelId = "chat_head_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Stremini Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Stremini AI")
            .setContentText("Active - Tap to open")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        serviceScope.cancel()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        hideFloatingChatbot()
        scanningOverlayView?.destroy()
        scanningOverlayView = null
        if (::overlayView.isInitialized && overlayView.windowToken != null) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
        }
    }
}
