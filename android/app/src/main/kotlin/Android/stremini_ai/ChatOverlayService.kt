package Android.stremini_ai

import android.animation.ValueAnimator
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
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
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
        const val ACTION_SEND_MESSAGE = "Android.stremini_ai.SEND_MESSAGE"
        const val EXTRA_MESSAGE = "message"
        const val ACTION_SCANNER_START = "Android.stremini_ai.SCANNER_START"
        const val ACTION_SCANNER_STOP = "Android.stremini_ai.SCANNER_STOP"
        val NEON_BLUE: Int = android.graphics.Color.parseColor("#00D9FF")
        val WHITE: Int = android.graphics.Color.parseColor("#FFFFFF")
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
    
    private lateinit var scannerChannel: android.view.inputmethod.InputMethodManager

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var hasMoved = false

    private val bubbleSizeDp = 70f
    private val menuItemSizeDp = 56f
    private val radiusDp = 80f

    private var lastCollapsedX = 0
    private var lastCollapsedY = 200

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
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        startForegroundService()
        setupOverlay()

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
    }

    private fun setupOverlay() {
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

        // CRITICAL FIX: Always use the FULL expanded container size
        // This prevents any size changes that cause position jumps
        val radiusPx = dpToPx(radiusDp).toFloat()
        val bubbleSizePx = dpToPx(bubbleSizeDp).toFloat()
        val expandedWindowSizePx = ((radiusPx * 2) + bubbleSizePx + dpToPx(20f)).toInt()

        params = WindowManager.LayoutParams(
            expandedWindowSizePx,  // Always use expanded size
            expandedWindowSizePx,  // Always use expanded size
            typeParam,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or  // CRITICAL: Allow touches to pass through
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or  // Watch for outside touches
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        
        // Position the container so bubble appears at lastCollapsed position
        val offsetPx = ((expandedWindowSizePx / 2) - (dpToPx(bubbleSizeDp) / 2))
        params.x = lastCollapsedX - offsetPx
        params.y = lastCollapsedY - offsetPx

        bubbleIcon.setOnTouchListener(this)
        
        // Set circular border for main bubble icon
        val bubbleBackground = android.graphics.drawable.GradientDrawable()
        bubbleBackground.shape = android.graphics.drawable.GradientDrawable.OVAL
        bubbleBackground.setColor(android.graphics.Color.BLACK)  // Black background
        bubbleBackground.setStroke(dpToPx(3f), android.graphics.Color.parseColor("#23A6E2"))  // Neon blue borderwidth
        bubbleIcon.background = bubbleBackground

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
        menuItems.forEach { 
            it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            
            // Menu items have a circular BLACK background with no border
            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
            drawable.setColor(android.graphics.Color.BLACK)  // Black background only
            it.background = drawable
        }

        updateMenuItemsColor()
        
        // Make the overlay root view transparent to touches except on bubble and menu items
        overlayView.setOnTouchListener { view, event ->
            // Get touch coordinates relative to the overlay view
            val x = event.x
            val y = event.y
            
            // Check if touch is on bubble icon using local coordinates
            val bubbleLeft = bubbleIcon.left.toFloat()
            val bubbleTop = bubbleIcon.top.toFloat()
            val bubbleRight = bubbleLeft + bubbleIcon.width
            val bubbleBottom = bubbleTop + bubbleIcon.height
            
            if (x >= bubbleLeft && x <= bubbleRight && y >= bubbleTop && y <= bubbleBottom) {
                // Forward touch to bubble icon
                val bubbleX = x - bubbleLeft
                val bubbleY = y - bubbleTop
                val bubbleEvent = MotionEvent.obtain(event)
                bubbleEvent.setLocation(bubbleX, bubbleY)
                val handled = bubbleIcon.onTouchEvent(bubbleEvent)
                bubbleEvent.recycle()
                return@setOnTouchListener handled
            }
            
            // Check if touch is on any visible menu item
            if (isMenuExpanded) {
                for (menuItem in menuItems) {
                    if (menuItem.visibility == View.VISIBLE && menuItem.alpha > 0.5f) {
                        val itemLeft = menuItem.left.toFloat() + menuItem.translationX
                        val itemTop = menuItem.top.toFloat() + menuItem.translationY
                        val itemRight = itemLeft + menuItem.width
                        val itemBottom = itemTop + menuItem.height
                        
                        if (x >= itemLeft && x <= itemRight && y >= itemTop && y <= itemBottom) {
                            // Forward touch to menu item
                            val itemX = x - itemLeft
                            val itemY = y - itemTop
                            val itemEvent = MotionEvent.obtain(event)
                            itemEvent.setLocation(itemX, itemY)
                            val handled = menuItem.onTouchEvent(itemEvent)
                            itemEvent.recycle()
                            return@setOnTouchListener handled
                        }
                    }
                }
            }
            
            // Touch is outside bubble/menu - DON'T consume, pass through to app below
            false
        }
        
        windowManager.addView(overlayView, params)
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
            windowManager.removeView(view)
            floatingChatView = null
            floatingChatParams = null
            isChatbotVisible = false
        }
    }

    private fun handleScanner() {
        isScannerActive = !isScannerActive
        updateMenuItemsColor()
        
        if (isScannerActive) {
            // Start the ScreenReaderService for screen detection
            val intent = Intent(this, ScreenReaderService::class.java)
            intent.action = ScreenReaderService.ACTION_START_SCAN
            startService(intent)
            Toast.makeText(this, "Screen Detection Enabled", Toast.LENGTH_SHORT).show()
        } else {
            // Stop the ScreenReaderService
            val intent = Intent(this, ScreenReaderService::class.java)
            intent.action = ScreenReaderService.ACTION_STOP_SCAN
            startService(intent)
            Toast.makeText(this, "Screen Detection Disabled", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun invokeFlutterMethod(methodName: String) {
        try {
            val intent = Intent(ACTION_SCANNER_START).apply {
                setPackage(packageName)
                putExtra("method", methodName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleVoiceCommand() {
        toggleFeature(menuItems[4].id)
        
        if (isFeatureActive(menuItems[4].id)) {
            // Open the Keyboard/Input Method Settings
            try {
                val intent = Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "Open Settings to enable AI Keyboard", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Fallback: Show input method picker
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
        // Clear all active features
        activeFeatures.clear()
        
        // Stop scanner if it's running
        if (isScannerActive) {
            isScannerActive = false
            val intent = Intent(this, ScreenReaderService::class.java)
            intent.action = ScreenReaderService.ACTION_STOP_SCAN
            startService(intent)
        }
        
        // Hide chatbot if visible
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
                // Active: Black background with neon blue glow overlay (no border)
                val layers = arrayOf(
                    // Layer 1: Black circle background
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(android.graphics.Color.BLACK)
                    },
                    // Layer 2: Neon blue glow overlay
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(NEON_BLUE)
                        alpha = 180  // Semi-transparent for glow effect (0-255)
                    }
                )
                item.background = android.graphics.drawable.LayerDrawable(layers)
                item.setColorFilter(WHITE)  // White icon
            } else {
                // Inactive: Black background only (no border)
                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                drawable.setColor(android.graphics.Color.BLACK)
                item.background = drawable
                item.setColorFilter(WHITE)  // White icon
            }
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                hasMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > 10 || abs(dy) > 10) {
                    hasMoved = true
                    if (!isMenuExpanded) {
                        isDragging = true
                        // Calculate where the bubble should be on screen
                        val radiusPx = dpToPx(radiusDp).toFloat()
                        val bubbleSizePx = dpToPx(bubbleSizeDp).toFloat()
                        val expandedWindowSizePx = ((radiusPx * 2) + bubbleSizePx + dpToPx(20f))
                        val offsetPx = (expandedWindowSizePx / 2) - (bubbleSizePx / 2)
                        
                        // Move container so bubble follows touch
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(overlayView, params)
                        
                        // Update lastCollapsed to bubble's screen position
                        lastCollapsedX = params.x + offsetPx.toInt()
                        lastCollapsedY = params.y + offsetPx.toInt()
                    } else {
                        collapseMenu()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!hasMoved && !isDragging) {
                    toggleMenu()
                } else if (isDragging) {
                    snapToEdge()
                }
                isDragging = false
                hasMoved = false
                return true
            }
        }
        return false
    }

    private fun toggleMenu() {
        if (isMenuExpanded) collapseMenu() else expandMenu()
    }

    private fun expandMenu() {
        isMenuExpanded = true

        val radiusPx = dpToPx(radiusDp).toFloat()
        val bubbleSizePx = dpToPx(bubbleSizeDp).toFloat()
        val menuItemSizePx = dpToPx(menuItemSizeDp).toFloat()

        val expandedWindowSizePx = (radiusPx * 2) + bubbleSizePx + dpToPx(20f)
        val centerX = expandedWindowSizePx / 2f
        val centerY = expandedWindowSizePx / 2f

        // Detect which side of screen the bubble is on
        val screenWidth = resources.displayMetrics.widthPixels
        val offsetPx = (expandedWindowSizePx / 2) - (bubbleSizePx / 2)
        val bubbleScreenX = params.x + offsetPx.toInt()
        val bubbleCenterX = bubbleScreenX + (bubbleSizePx / 2)
        val isOnRightSide = bubbleCenterX > (screenWidth / 2)

        // CONSISTENT ICON ORDER: Icons stay in same position visually
        // Right side: opens left
        // Left side: opens right (mirrored angles)
        val fixedAngles = if (isOnRightSide) {
            // Right side - opens left (top to bottom)
            listOf(90.0, 135.0, 180.0, 225.0, 270.0)
        } else {
            // Left side - opens right (top to bottom, same visual order)
            listOf(90.0, 45.0, 0.0, -45.0, -90.0)
        }

        for ((index, view) in menuItems.withIndex()) {
            view.visibility = View.VISIBLE
            view.alpha = 0f

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
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        updateMenuItemsColor()
    }

    private fun collapseMenu() {
        isMenuExpanded = false

        for (view in menuItems) {
            view.animate()
                .translationX(0f)
                .translationY(0f)
                .alpha(0f)
                .setDuration(150)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { view.visibility = View.GONE }
                .start()
        }
    }

    private fun snapToEdge() {
        val bubbleSizePx = dpToPx(bubbleSizeDp).toFloat()
        val radiusPx = dpToPx(radiusDp).toFloat()
        val expandedWindowSizePx = (radiusPx * 2) + bubbleSizePx + dpToPx(20f)
        val offsetPx = (expandedWindowSizePx / 2) - (bubbleSizePx / 2)
        
        val screenWidth = resources.displayMetrics.widthPixels

        // Calculate bubble's current screen position
        val currentBubbleScreenX = params.x + offsetPx.toInt()
        val currentBubbleCenterX = currentBubbleScreenX + (bubbleSizePx / 2)
        val middle = screenWidth / 2

        // Calculate target bubble screen position
        val targetBubbleScreenX = if (currentBubbleCenterX > middle) {
            screenWidth - bubbleSizePx.toInt()
        } else {
            0
        }

        // Calculate target container position
        val targetContainerX = targetBubbleScreenX - offsetPx.toInt()

        ValueAnimator.ofInt(params.x, targetContainerX).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                // Update lastCollapsed to bubble's screen position
                lastCollapsedX = params.x + offsetPx.toInt()
                windowManager.updateViewLayout(overlayView, params)
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
        serviceScope.cancel()
        unregisterReceiver(controlReceiver)
        hideFloatingChatbot()
        if (::overlayView.isInitialized && overlayView.windowToken != null) windowManager.removeView(overlayView)
    }
}