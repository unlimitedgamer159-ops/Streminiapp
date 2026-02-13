package Android.stremini_ai

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
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class ScreenReaderService : AccessibilityService() {

    companion object {
        const val ACTION_START_SCAN = "Android.stremini_ai.START_SCAN"
        const val ACTION_STOP_SCAN = "Android.stremini_ai.STOP_SCAN"
        const val ACTION_SCAN_COMPLETE = "Android.stremini_ai.SCAN_COMPLETE"
        const val EXTRA_SCANNED_TEXT = "scanned_text"
        private const val TAG = "ScreenReaderService"
        
        private var instance: ScreenReaderService? = null
        fun isRunning(): Boolean = instance != null
        fun isScanningActive(): Boolean = instance?.isScanning == true
        
        // --- THEME COLORS ---
        // Safe Theme (Green)
        private val SAFE_BG_COLOR = Color.parseColor("#1A3826")      // Dark Green Background
        private val SAFE_BORDER_COLOR = Color.parseColor("#2D5C43")  // Lighter Green Border
        private val SAFE_TEXT_COLOR = Color.parseColor("#6DD58C")    // Bright Green Text
        
        // Danger Theme (Brown/Red)
        private val DANGER_BG_COLOR = Color.parseColor("#38261A")    // Dark Brown/Red Background
        private val DANGER_BORDER_COLOR = Color.parseColor("#5C432D") // Lighter Brown Border
        private val DANGER_TEXT_COLOR = Color.parseColor("#FFD580")  // Orange/Gold Text
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

    // Data Models
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
        val url: String?,
        val message: String?
    )

    data class ContentWithPosition(
        val text: String, 
        val bounds: Rect,
        val area: Int // Helper for sorting
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> {
                if (tagsVisible) clearTags()
                if (!isScanning) startScreenScan()
            }
            ACTION_STOP_SCAN -> clearAllOverlays()
        }
        return START_NOT_STICKY
    }

    private fun startScreenScan() {
        isScanning = true
        showScanningAnimation()

        serviceScope.launch {
            try {
                // Wait for overlay to appear / app switching
                delay(800) 
                
                val rootNode = rootInActiveWindow
                if (rootNode == null) {
                    hideScanningAnimation()
                    isScanning = false
                    return@launch
                }

                val contentList = mutableListOf<ContentWithPosition>()
                extractContentWithPositions(rootNode, contentList)
                rootNode.recycle()

                val fullText = contentList.joinToString("\n") { it.text }
                
                // 1. ANALYZE (API Call with Fallback)
                val result = analyzeScreenContent(fullText)
                
                // 2. DISPLAY RESULTS
                hideScanningAnimation()
                displayTagsForAllThreats(contentList, result)

                // 3. BROADCAST TO FLUTTER
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
    
    // Fallback analysis if API fails or for offline testing
    private fun performLocalAnalysis(text: String): ScanResult {
        val lower = text.lowercase()
        val tags = mutableListOf<TaggedElement>()
        var isSafe = true
        
        // Simple keywords for demonstration
        val threats = listOf("scam", "winner", "prize", "urgent", "password", "bank", "verify", "pirate", "crack")
        
        threats.forEach { threat ->
            if (lower.contains(threat)) {
                isSafe = false
                tags.add(TaggedElement(
                    label = "Suspicious Keyword", 
                    color = DANGER_TEXT_COLOR, 
                    reason = threat, 
                    url = null, 
                    message = threat
                ))
            }
        }
        
        return ScanResult(isSafe, if(isSafe) "safe" else "warning", "Analysis Complete", tags)
    }

    private suspend fun analyzeScreenContent(content: String): ScanResult = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("text", content.take(5000)) // Changed key to 'text' to match backend expectation
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://ai-keyboard-backend.vishwajeetadkine705.workers.dev/security/analyze/text") // Updated Endpoint
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                 return@withContext performLocalAnalysis(content)
            }

            val responseData = response.body?.string() ?: ""
            val json = JSONObject(responseData)

            // Parse Backend Response
            val isThreat = json.optBoolean("is_threat", false)
            val detailsArray = json.optJSONArray("details")
            val tags = mutableListOf<TaggedElement>()

            if (detailsArray != null) {
                for (i in 0 until detailsArray.length()) {
                    val detail = detailsArray.getString(i)
                    // Create a tag for each detail found. 
                    // Note: We need to map the "detail" back to a keyword if possible, 
                    // or just tag the whole screen if specific mapping isn't available.
                    // For this logic, we will try to find the keyword within the detail string.
                    
                    tags.add(TaggedElement(
                        label = "Alert",
                        color = DANGER_TEXT_COLOR,
                        reason = detail, // Using the detail itself as the "keyword" search might be too broad, but serves as fallback
                        url = null,
                        message = detail
                    ))
                }
            }

            ScanResult(
                isSafe = !isThreat,
                riskLevel = json.optString("type", "safe"),
                summary = "Confidence: ${json.optDouble("confidence", 0.0)}",
                taggedElements = tags
            )
        } catch (e: Exception) {
            Log.e(TAG, "Analysis error", e)
            performLocalAnalysis(content)
        }
    }

    // ==========================================
    // UI DISPLAY LOGIC
    // ==========================================
    private fun displayTagsForAllThreats(contentList: List<ContentWithPosition>, result: ScanResult) {
        clearTags()
        
        // 1. Setup Full Screen Container
        tagsContainer = FrameLayout(this)
        val containerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, 
            PixelFormat.TRANSLUCENT
        )
        // Ensure gravity is Top-Left so margins act as absolute coordinates
        containerParams.gravity = Gravity.TOP or Gravity.START 
        containerParams.x = 0
        containerParams.y = 0
        windowManager.addView(tagsContainer, containerParams)

        // 2. Banner Logic (Top of screen)
        val bannerTitle = if (result.isSafe) "Safe: No Threat Detected" else "Warning: Potential Threats"
        val bannerBg = if (result.isSafe) SAFE_BG_COLOR else DANGER_BG_COLOR
        val bannerBorder = if (result.isSafe) SAFE_BORDER_COLOR else DANGER_BORDER_COLOR
        val bannerText = if (result.isSafe) SAFE_TEXT_COLOR else DANGER_TEXT_COLOR
        
        createBanner(bannerTitle, bannerBg, bannerBorder, bannerText)

        // 3. Tag Logic
        if (!result.isSafe) {
            result.taggedElements.forEach { tag ->
                // IMPROVED MATCHING:
                // Find ALL elements that contain the threat text.
                // We split the reason into keywords to find a match on screen.
                // E.g. "Investment scam pattern" -> look for "Investment" or matches in the text
                
                // Simple heuristic: Try to match the exact phrase, if not, try partials.
                // For this implementation, we rely on the logic that 'reason' might be a keyword.
                // In a real scenario, the backend should return the "snippet" that triggered the alert.
                
                // As a robust fallback, we search for the MOST SPECIFIC element (smallest area)
                // that contains relevant text to the threat.
                
                // Just for Demo: scanning the contentList for common scam words if the tag doesn't provide specific location text
                val searchTerms = tag.reason.split(" ").filter { it.length > 4 }
                
                var bestMatch: ContentWithPosition? = null
                
                // Try to find a node that matches the specific tag reason
                val directMatches = contentList.filter { content -> 
                    content.text.contains(tag.reason, ignoreCase = true) 
                }
                
                if (directMatches.isNotEmpty()) {
                    // Pick the smallest node (likely the specific UI element, not the whole page)
                    bestMatch = directMatches.minByOrNull { it.area }
                } else {
                    // Fallback: search for keywords from the reason
                    for (term in searchTerms) {
                        val termMatches = contentList.filter { it.text.contains(term, ignoreCase = true) }
                        if (termMatches.isNotEmpty()) {
                            bestMatch = termMatches.minByOrNull { it.area }
                            break // Found a match for a keyword
                        }
                    }
                }

                if (bestMatch != null) {
                    createFloatingTag(bestMatch.bounds, tag.label, DANGER_BG_COLOR, DANGER_BORDER_COLOR, DANGER_TEXT_COLOR, tag.reason)
                }
            }
        } else {
            // Optional: Tag known safe elements to reassure user
            val safeMatches = contentList.filter { 
                it.text.contains("google", ignoreCase = true) || it.text.contains("wikipedia", ignoreCase = true) 
            }
            
            // Limit safe tags to avoid clutter
            safeMatches.minByOrNull { it.area }?.let { match ->
                 createFloatingTag(match.bounds, "Verified Safe", SAFE_BG_COLOR, SAFE_BORDER_COLOR, SAFE_TEXT_COLOR, "Source Verified")
            }
        }
    }

    // ==========================================
    // UI COMPONENTS
    // ==========================================

    private fun createBanner(title: String, bgColor: Int, borderColor: Int, textColor: Int) {
        val context = this
        val bannerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            
            background = GradientDrawable().apply {
                setColor(bgColor) 
                setStroke(dpToPx(2), borderColor)
                cornerRadius = dpToPx(24).toFloat()
            }
            elevation = 10f
        }

        val iconView = TextView(context).apply {
            text = if(title.contains("Safe")) "🛡️" else "⚠️"
            textSize = 18f
            setPadding(0, 0, dpToPx(8), 0)
            setTextColor(Color.WHITE)
        }
        
        val titleView = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        
        val statusView = TextView(context).apply {
            text = if(title.contains("Safe")) "SECURE" else "RISK"
            setTextColor(textColor)
            textSize = 12f
            setPadding(dpToPx(10), 0, 0, 0)
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        
        bannerLayout.addView(iconView)
        bannerLayout.addView(titleView)
        bannerLayout.addView(statusView)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(50) // Status bar clear
        }

        tagsContainer?.addView(bannerLayout, params)
    }

    private fun createFloatingTag(bounds: Rect, labelText: String, bgColor: Int, borderColor: Int, textColor: Int, subText: String? = null) {
        val context = this
        
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL // Changed to Vertical to support subtext
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dpToPx(8).toFloat()
                setStroke(dpToPx(1), borderColor)
            }
            elevation = 15f
        }
        
        // Header Row (Icon + Label)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = TextView(context).apply {
            text = if (bgColor == SAFE_BG_COLOR) "✓" else "!"
            setTextColor(textColor)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, dpToPx(4), 0)
        }

        val label = TextView(context).apply {
            text = labelText
            setTextColor(textColor)
            textSize = 12f
            setTypeface(Typeface.DEFAULT_BOLD)
            maxLines = 1
        }
        
        header.addView(icon)
        header.addView(label)
        pill.addView(header)

        // Subtext (Reason)
        if (!subText.isNullOrEmpty()) {
            val subMsg = TextView(context).apply {
                text = subText
                setTextColor(Color.parseColor("#DDDDDD"))
                textSize = 10f
                maxLines = 2
                gravity = Gravity.CENTER
            }
            pill.addView(subMsg)
        }

        // --- POSITIONING LOGIC ---
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        
        params.gravity = Gravity.TOP or Gravity.START // Crucial for absolute positioning via margins
        params.leftMargin = bounds.left.coerceAtLeast(0) // Align with left of element
        
        // Calculate Y Position:
        // Try to place it ABOVE the element.
        // If element is too high (near top banner), place it BELOW.
        
        val tagHeightEstimate = dpToPx(40) // Rough height of our tag
        val bannerZone = dpToPx(110) // Approx height of banner + status bar area
        
        if (bounds.top - tagHeightEstimate > bannerZone) {
            // Place Above
            params.topMargin = bounds.top - tagHeightEstimate - dpToPx(5)
        } else {
            // Place Below
            params.topMargin = bounds.bottom + dpToPx(5)
        }

        tagsContainer?.addView(pill, params)
    }

    private fun extractContentWithPositions(node: AccessibilityNodeInfo, list: MutableList<ContentWithPosition>) {
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank() && text.trim().length > 2) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            // Filter out invalid/invisible elements
            if (bounds.width() > 10 && bounds.height() > 10 && bounds.left >= 0 && bounds.top >= 0) {
                 list.add(ContentWithPosition(
                     text = text.trim(), 
                     bounds = bounds,
                     area = bounds.width() * bounds.height()
                 ))
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
                    setColor(Color.parseColor("#60000000")) // Dim background
                }
            }
            
            val loadingText = TextView(this).apply {
                 text = "Analyzing..."
                 setTextColor(Color.WHITE)
                 textSize = 20f
                 gravity = Gravity.CENTER
            }
            val textParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            scanView.addView(loadingText, textParams)
            
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
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
