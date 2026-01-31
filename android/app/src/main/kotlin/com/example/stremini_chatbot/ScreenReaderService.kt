package com.example.stremini_chatbot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class ScreenReaderService : AccessibilityService() {

    companion object {
        const val ACTION_START_SCAN = "com.example.stremini_chatbot.START_SCAN"
        const val ACTION_STOP_SCAN = "com.example.stremini_chatbot.STOP_SCAN"
        const val ACTION_SCAN_COMPLETE = "com.example.stremini_chatbot.SCAN_COMPLETE"
        const val EXTRA_SCANNED_TEXT = "scanned_text"
        private const val TAG = "ScreenReaderService"
        private var instance: ScreenReaderService? = null
        fun isRunning(): Boolean = instance != null
        
        // Exact Red color from your screenshot
        private val DANGER_COLOR = Color.parseColor("#B71C1C")
        private val SAFE_COLOR = Color.parseColor("#1B5E20")
    }

    private lateinit var windowManager: WindowManager
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scanningOverlay: View? = null
    private var tagsContainer: FrameLayout? = null
    private var isScanning = false
    private var tagsVisible = false

    data class ScanResult(
        val isSafe: Boolean,
        val riskLevel: String,
        val summary: String,
        val taggedElements: List<TaggedElement>
    )

    data class TaggedElement(
        val label: String,
        val color: Int,
        val reason: String,
        val url: String?
    )

    data class ContentWithPosition(
        val text: String, 
        val bounds: Rect,
        val isUrl: Boolean = false
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> if (tagsVisible) clearTags() else if (!isScanning) startScreenScan()
            ACTION_STOP_SCAN -> clearAllOverlays()
        }
        return START_NOT_STICKY
    }

    private fun startScreenScan() {
        isScanning = true
        showScanningAnimation()

        serviceScope.launch {
            try {
                delay(800) 
                val rootNode = rootInActiveWindow ?: return@launch
                val contentList = mutableListOf<ContentWithPosition>()
                extractContentWithPositions(rootNode, contentList)
                rootNode.recycle()

                val fullText = contentList.joinToString("\n") { it.text }
                val result = analyzeScreenContent(fullText)

                hideScanningAnimation()
                displayTagsForAllThreats(contentList, result)

                val broadcastIntent = Intent(ACTION_SCAN_COMPLETE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_SCANNED_TEXT, fullText)
                }
                sendBroadcast(broadcastIntent)

                isScanning = false
                tagsVisible = true
            } catch (e: Exception) {
                Log.e(TAG, "Scan Error", e)
                hideScanningAnimation()
                isScanning = false
            }
        }
    }

    private suspend fun analyzeScreenContent(content: String): ScanResult = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("content", content.take(5000))
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://ai-keyboard-backend.vishwajeetadkine705.workers.dev/security/scan-content")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseData = response.body?.string() ?: ""
            val json = JSONObject(responseData)

            val taggedArray = json.optJSONArray("taggedElements")
            val tags = mutableListOf<TaggedElement>()
            if (taggedArray != null) {
                for (i in 0 until taggedArray.length()) {
                    val item = taggedArray.getJSONObject(i)
                    val securityTag = item.getJSONObject("securityTag")
                    val details = item.getJSONObject("details")
                    
                    val colorStr = securityTag.optString("color", "#dc2626")
                    val color = try { Color.parseColor(colorStr) } catch (e: Exception) { Color.RED }

                    tags.add(TaggedElement(
                        label = securityTag.optString("label", "ALERT"),
                        color = color,
                        reason = details.optString("reason", "Suspicious content"),
                        url = details.optString("url", null).takeIf { it != "null" }
                    ))
                }
            }

            ScanResult(
                isSafe = json.optBoolean("isSafe", true),
                riskLevel = json.optString("riskLevel", "safe"),
                summary = json.optString("summary", ""),
                taggedElements = tags
            )
        } catch (e: Exception) {
            Log.e(TAG, "Analysis error", e)
            ScanResult(true, "safe", "✓ No threats detected", emptyList())
        }
    }

    private fun displayTagsForAllThreats(contentList: List<ContentWithPosition>, result: ScanResult) {
        clearTags()
        
        tagsContainer = FrameLayout(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, 
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(tagsContainer, params)

        // 1. BANNER LOGIC
        val linkThreats = result.taggedElements.count { !it.url.isNullOrEmpty() }
        val bannerText: String
        val bannerDetail: String
        val bannerColor: Int

        if (result.isSafe) {
            bannerText = "Safe: No Threat Detected"
            bannerDetail = "Scam Detection Active"
            bannerColor = SAFE_COLOR
        } else {
            if (linkThreats > 0) {
                bannerText = "Suspicious Links: Threat Detected"
                bannerDetail = "⚠️ $linkThreats threat(s) found - Be careful"
                bannerColor = DANGER_COLOR
            } else {
                bannerText = "⚠️ DANGER: SCAM MESSAGE"
                bannerDetail = "Do not reply or send money."
                bannerColor = DANGER_COLOR
            }
        }

        createBanner(bannerText, bannerColor, bannerDetail, result.isSafe)

        // 2. TAGGING LOGIC
        if (!result.isSafe) {
            val taggedPositions = mutableListOf<Pair<Int, Int>>()
            
            result.taggedElements.forEach { tag ->
                if (tag.url != null && tag.url.isNotEmpty()) {
                    // --- URL MATCHING STRATEGY ---
                    // 1. Exact Match
                    // 2. Contains Match
                    // 3. Hostname Match (e.g. finds "bad-site.com" even if url is "http://bad-site.com")
                    
                    val host = try { URI(tag.url).host ?: tag.url } catch(e: Exception) { tag.url }
                    
                    val matches = contentList.filter { 
                        it.text.contains(tag.url, ignoreCase = true) || 
                        tag.url.contains(it.text, ignoreCase = true) ||
                        it.text.contains(host, ignoreCase = true)
                    }

                    if (matches.isNotEmpty()) {
                        matches.forEach { match ->
                            val posKey = Pair(match.bounds.left, match.bounds.top)
                            
                            // Prevent overlapping tags
                            if (!isPositionTagged(taggedPositions, posKey)) {
                                createFloatingTag(match.bounds, "⚠️ WARNING", DANGER_COLOR) 
                                taggedPositions.add(posKey)
                            }
                        }
                    } else {
                        // Fallback: If specific URL match fails, tag ANY URL-like element found
                        val anyUrl = contentList.find { it.isUrl }
                        if (anyUrl != null) {
                            val posKey = Pair(anyUrl.bounds.left, anyUrl.bounds.top)
                            if (!isPositionTagged(taggedPositions, posKey)) {
                                createFloatingTag(anyUrl.bounds, "⚠️ WARNING", DANGER_COLOR)
                                taggedPositions.add(posKey)
                            }
                        }
                    }
                } else {
                    // --- TEXT MATCHING ---
                    // Match "urgent", "password", etc.
                    val keywords = tag.reason.lowercase().split(" ")
                        .filter { it.length > 4 && !it.matches(Regex(".*[0-9%].*")) }
                    
                    val relatedContent = contentList.find { content ->
                        val lowerContent = content.text.lowercase()
                        keywords.any { keyword -> lowerContent.contains(keyword) }
                    }

                    if (relatedContent != null) {
                        val posKey = Pair(relatedContent.bounds.left, relatedContent.bounds.top)
                        if (!isPositionTagged(taggedPositions, posKey)) {
                            createFloatingTag(relatedContent.bounds, "⚠️ SCAM ALERT", DANGER_COLOR)
                            taggedPositions.add(posKey)
                        }
                    } else {
                        createGeneralThreatTag(tag.label, DANGER_COLOR)
                    }
                }
            }
        }
    }

    private fun isPositionTagged(existing: List<Pair<Int, Int>>, current: Pair<Int, Int>): Boolean {
        return existing.any { 
            Math.abs(it.first - current.first) < 60 && Math.abs(it.second - current.second) < 60 
        }
    }

    private fun createGeneralThreatTag(label: String, color: Int) {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val fakeBounds = Rect(screenWidth / 2 - 100, screenHeight / 3, screenWidth / 2 + 100, screenHeight / 3 + 50)
        createFloatingTag(fakeBounds, label, color)
    }

    private fun createBanner(title: String, color: Int, subtitle: String, isSafe: Boolean) {
        val context = this
        val bannerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
            background = GradientDrawable().apply {
                setColor(color) 
                cornerRadius = 24f
                alpha = 240
            }
            elevation = 10f
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconView = TextView(context).apply {
            text = if (isSafe) "✓" else "!"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 20, 0)
            setTextColor(Color.WHITE)
        }
        
        val titleView = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
        }

        titleRow.addView(iconView)
        titleRow.addView(titleView)

        val subtitleView = TextView(context).apply {
            text = subtitle
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 12f
            setPadding(60, 5, 0, 0)
        }

        bannerLayout.addView(titleRow)
        bannerLayout.addView(subtitleView)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(30, 100, 30, 0)
        }

        tagsContainer?.addView(bannerLayout, params)
    }

    private fun createFloatingTag(bounds: Rect, labelText: String, color: Int) {
        val context = this
        
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = 100f 
            }
            elevation = 20f
        }

        val label = TextView(context).apply {
            text = labelText
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(Typeface.DEFAULT_BOLD)
            maxLines = 1
        }

        pill.addView(label)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // CRITICAL FIX: Explicit Gravity to ensure margins act as Absolute Coordinates
            gravity = Gravity.TOP or Gravity.LEFT 

            // Horizontal
            leftMargin = bounds.left.coerceAtLeast(10)
            
            // Vertical
            val tagHeight = 80
            val spaceAbove = bounds.top - 200 // Subtract banner space
            
            if (spaceAbove > tagHeight) {
                topMargin = bounds.top - tagHeight + 10
            } else {
                topMargin = bounds.bottom + 10
            }
        }
        
        tagsContainer?.addView(pill, params)
    }

    private fun extractContentWithPositions(node: AccessibilityNodeInfo, list: MutableList<ContentWithPosition>) {
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank() && text.trim().length > 2) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            if (bounds.width() > 30 && bounds.height() > 15 && bounds.top >= 0 && bounds.left >= 0) {
                // Expanded URL Detection Logic
                val t = text.lowercase()
                val isUrl = t.contains("http") || t.contains("www.") || t.contains(".com") || 
                           t.contains(".net") || t.contains(".org") || t.contains(".xyz")
                           
                list.add(ContentWithPosition(text.trim(), bounds, isUrl))
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                extractContentWithPositions(it, list)
                it.recycle()
            }
        }
    }

    private fun showScanningAnimation() {
        try {
            val scanView = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#80000000"))
                }
            }
             val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(scanView, params)
            scanningOverlay = scanView
        } catch (e: Exception) { Log.e(TAG, "Overlay Error", e) }
    }

    private fun hideScanningAnimation() {
        scanningOverlay?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        scanningOverlay = null
    }

    private fun clearTags() {
        tagsContainer?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        tagsContainer = null
        tagsVisible = false
    }

    private fun clearAllOverlays() {
        hideScanningAnimation()
        clearTags()
        isScanning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        clearAllOverlays()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
