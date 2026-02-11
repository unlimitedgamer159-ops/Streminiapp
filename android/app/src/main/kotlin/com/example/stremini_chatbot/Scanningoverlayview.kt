package com.example.stremini_chatbot

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView

class ScanningOverlayView(private val context: Context) {
    
    companion object {
        const val TAG = "ScanningOverlay"
    }
    
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isShowing = false
    
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var statusText: TextView? = null
    private var resultCard: CardView? = null
    private var resultTitle: TextView? = null
    private var resultMessage: TextView? = null
    private var resultDetails: TextView? = null
    
    private var pulseAnimator: ValueAnimator? = null
    
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenReaderService.ACTION_SCAN_STARTED -> {
                    showScanningAnimation()
                }
                ScreenReaderService.ACTION_SCAN_PROGRESS -> {
                    val progress = intent.getIntExtra(ScreenReaderService.EXTRA_PROGRESS, 0)
                    updateProgress(progress)
                }
                ScreenReaderService.ACTION_SCAN_RESULT -> {
                    val isThreat = intent.getBooleanExtra(ScreenReaderService.EXTRA_IS_THREAT, false)
                    val type = intent.getStringExtra(ScreenReaderService.EXTRA_THREAT_TYPE) ?: "safe"
                    val message = intent.getStringExtra(ScreenReaderService.EXTRA_MESSAGE) ?: ""
                    val details = intent.getStringArrayExtra(ScreenReaderService.EXTRA_DETAILS) ?: emptyArray()
                    val confidence = intent.getDoubleExtra(ScreenReaderService.EXTRA_CONFIDENCE, 0.0)
                    
                    showResult(isThreat, type, message, details.toList(), confidence)
                }
                ScreenReaderService.ACTION_SCAN_COMPLETE -> {
                    // Auto-hide after showing result
                    overlayView?.postDelayed({ hide() }, 3000)
                }
            }
        }
    }
    
    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        registerReceiver()
    }
    
    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ScreenReaderService.ACTION_SCAN_STARTED)
            addAction(ScreenReaderService.ACTION_SCAN_PROGRESS)
            addAction(ScreenReaderService.ACTION_SCAN_RESULT)
            addAction(ScreenReaderService.ACTION_SCAN_COMPLETE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(scanReceiver, filter)
        }
    }
    
    private fun showScanningAnimation() {
        if (isShowing) return
        
        try {
            // Create overlay view
            overlayView = createScanningView()
            
            val typeParam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                typeParam,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            
            params.gravity = Gravity.CENTER
            
            windowManager?.addView(overlayView, params)
            isShowing = true
            
            startPulseAnimation()
            
            Log.d(TAG, "Scanning animation shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing scanning animation", e)
        }
    }
    
    private fun createScanningView(): View {
        val rootLayout = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#DD000000"))
        }
        
        val cardView = CardView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(320),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            radius = dpToPx(24).toFloat()
            cardElevation = dpToPx(8).toFloat()
            setCardBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        
        val contentLayout = FrameLayout(context).apply {
            setPadding(dpToPx(32), dpToPx(32), dpToPx(32), dpToPx(32))
        }
        
        // Scanning section (initially visible)
        val scanningSection = createScanningSection()
        
        // Result section (initially hidden)
        resultCard = createResultSection()
        resultCard?.visibility = View.GONE
        
        contentLayout.addView(scanningSection)
        contentLayout.addView(resultCard)
        
        cardView.addView(contentLayout)
        rootLayout.addView(cardView)
        
        return rootLayout
    }
    
    private fun createScanningSection(): View {
        val layout = FrameLayout(context)
        
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        
        // Animated scanner icon
        val scannerIcon = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(80), dpToPx(80)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            background = context.getDrawable(android.R.drawable.ic_menu_search)?.apply {
                setTint(Color.parseColor("#00D9FF"))
            }
        }
        container.addView(scannerIcon)
        
        // Progress bar
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(8)
            ).apply {
                topMargin = dpToPx(24)
            }
            progressDrawable?.setTint(Color.parseColor("#00D9FF"))
            max = 100
            progress = 0
        }
        container.addView(progressBar)
        
        // Progress text
        progressText = TextView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
            text = "0%"
            textSize = 14f
            setTextColor(Color.parseColor("#00D9FF"))
        }
        container.addView(progressText)
        
        // Status text
        statusText = TextView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(16)
            }
            text = "Scanning Screen..."
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        container.addView(statusText)
        
        val subText = TextView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
            text = "AI is analyzing content for threats"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        container.addView(subText)
        
        layout.addView(container)
        return layout
    }
    
    private fun createResultSection(): CardView {
        val card = CardView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            radius = dpToPx(16).toFloat()
            cardElevation = dpToPx(4).toFloat()
        }
        
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))
        }
        
        resultTitle = TextView(context).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        container.addView(resultTitle)
        
        resultMessage = TextView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
        }
        container.addView(resultMessage)
        
        resultDetails = TextView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        container.addView(resultDetails)
        
        card.addView(container)
        return card
    }
    
    private fun updateProgress(progress: Int) {
        progressBar?.progress = progress
        progressText?.text = "$progress%"
        
        when {
            progress < 30 -> statusText?.text = "Reading screen content..."
            progress < 60 -> statusText?.text = "Analyzing text..."
            progress < 90 -> statusText?.text = "Checking for threats..."
            else -> statusText?.text = "Finalizing results..."
        }
    }
    
    private fun showResult(
        isThreat: Boolean,
        type: String,
        message: String,
        details: List<String>,
        confidence: Double
    ) {
        try {
            // Hide scanning section
            progressBar?.visibility = View.GONE
            progressText?.visibility = View.GONE
            statusText?.visibility = View.GONE
            
            // Show result section
            resultCard?.visibility = View.VISIBLE
            
            val emoji = when {
                isThreat && type == "danger" -> "🚨"
                isThreat && type == "warning" -> "⚠️"
                else -> "✅"
            }
            
            val titleText = when {
                isThreat && type == "danger" -> "CRITICAL THREAT DETECTED"
                isThreat && type == "warning" -> "WARNING - POTENTIAL THREAT"
                else -> "SCAN COMPLETE - SAFE"
            }
            
            val color = when {
                isThreat && type == "danger" -> Color.parseColor("#D32F2F")
                isThreat && type == "warning" -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#4CAF50")
            }
            
            resultTitle?.text = "$emoji $titleText"
            resultTitle?.setTextColor(color)
            
            resultMessage?.text = message
            
            if (details.isNotEmpty()) {
                resultDetails?.text = "Details:\n• " + details.joinToString("\n• ")
            } else {
                resultDetails?.visibility = View.GONE
            }
            
            resultCard?.setCardBackgroundColor(
                when {
                    isThreat && type == "danger" -> Color.parseColor("#331A1A")
                    isThreat && type == "warning" -> Color.parseColor("#332A1A")
                    else -> Color.parseColor("#1A331A")
                }
            )
            
            Log.d(TAG, "Result shown: $titleText")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing result", e)
        }
    }
    
    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0.7f, 1f).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                progressBar?.alpha = value
            }
            start()
        }
    }
    
    fun hide() {
        try {
            pulseAnimator?.cancel()
            
            if (isShowing && overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
                isShowing = false
                Log.d(TAG, "Overlay hidden")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding overlay", e)
        }
    }
    
    fun destroy() {
        hide()
        try {
            context.unregisterReceiver(scanReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }
    
    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
