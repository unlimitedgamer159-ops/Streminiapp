package com.example.stremini_chatbot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ScreenReaderService : AccessibilityService() {

    companion object {
        const val TAG = "ScreenReaderService"
        const val ACTION_START_SCAN = "com.example.stremini_chatbot.START_SCAN"
        const val ACTION_STOP_SCAN = "com.example.stremini_chatbot.STOP_SCAN"
        const val ACTION_PERFORM_SINGLE_SCAN = "com.example.stremini_chatbot.PERFORM_SINGLE_SCAN"
        
        // Broadcast actions for UI updates
        const val ACTION_SCAN_STARTED = "com.example.stremini_chatbot.SCAN_STARTED"
        const val ACTION_SCAN_PROGRESS = "com.example.stremini_chatbot.SCAN_PROGRESS"
        const val ACTION_SCAN_COMPLETE = "com.example.stremini_chatbot.SCAN_COMPLETE"
        const val ACTION_SCAN_RESULT = "com.example.stremini_chatbot.SCAN_RESULT"
        
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_IS_THREAT = "is_threat"
        const val EXTRA_THREAT_TYPE = "threat_type"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DETAILS = "details"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_TAGS_JSON = "tags_json"
        
        private const val API_URL = "https://ai-keyboard-backend.vishwajeetadkine705.workers.dev/security/analyze/text"
        private var isScanning = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> {
                isScanning = true
                Log.d(TAG, "Continuous scanning enabled")
            }
            ACTION_STOP_SCAN -> {
                isScanning = false
                scanJob?.cancel()
                Log.d(TAG, "Continuous scanning disabled")
            }
            ACTION_PERFORM_SINGLE_SCAN -> {
                // Perform immediate single scan
                Log.d(TAG, "Performing single scan...")
                serviceScope.launch {
                    performScanWithAnimation()
                }
            }
        }
        return START_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isScanning) return
        
        // Auto-scan on content changes
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            scanJob?.cancel()
            scanJob = serviceScope.launch {
                delay(2000)
                if (isScanning) {
                    performScanWithAnimation()
                }
            }
        }
    }

    private suspend fun performScanWithAnimation() {
        try {
            // Step 1: Send scan started broadcast
            sendBroadcast(Intent(ACTION_SCAN_STARTED))
            Log.d(TAG, "Scan started")
            
            // Step 2: Extract screen content with progress updates
            sendProgress(10)
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.e(TAG, "Cannot access screen content")
                sendScanComplete(false)
                return
            }
            
            sendProgress(30)
            val nodes = mutableListOf<ScreenTextNode>()
            extractText(rootNode, nodes)

            val texts = nodes.map { it.text }
            
            val content = texts.joinToString(" ")
            Log.d(TAG, "Extracted ${texts.size} text elements, ${content.length} chars")
            
            if (content.trim().length < 10) {
                Log.d(TAG, "Content too short, skipping scan")
                sendScanComplete(false)
                return
            }
            
            // Step 3: Call API with progress
            sendProgress(50)
            delay(500) // Slight delay for animation
            
            val result = callSecurityApi(content, nodes)
            
            sendProgress(90)
            delay(300)
            
            // Step 4: Send results
            sendProgress(100)
            if (result != null) {
                sendScanResult(result)
            } else {
                sendScanComplete(false)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
            sendScanComplete(false)
        }
    }

    private suspend fun callSecurityApi(text: String, nodes: List<ScreenTextNode>): ScanResult? {
        return try {
            Log.d(TAG, "Calling API: $API_URL")
            
            val jsonBody = JSONObject().apply {
                put("text", text)
                put("blocks", JSONArray().apply {
                    nodes.take(200).forEach { node ->
                        put(JSONObject().apply {
                            put("text", node.text)
                            put("left", node.bounds.left)
                            put("top", node.bounds.top)
                            put("right", node.bounds.right)
                            put("bottom", node.bounds.bottom)
                        })
                    }
                })
            }
            
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(API_URL)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                Log.d(TAG, "API Response: $responseBody")
                
                if (!responseBody.isNullOrEmpty()) {
                    val json = JSONObject(responseBody)
                    
                    ScanResult(
                        isThreat = json.optBoolean("is_threat", false),
                        type = json.optString("type", "safe"),
                        message = json.optString("message", "No threats detected"),
                        details = parseDetailsArray(json),
                        confidence = json.optDouble("confidence", 0.0),
                        tagsJson = buildTagsJson(json, nodes)
                    )
                } else null
            } else {
                Log.e(TAG, "API Error: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "API call failed", e)
            null
        }
    }

    private fun parseDetailsArray(json: JSONObject): List<String> {
        val details = mutableListOf<String>()
        try {
            val detailsArray = json.optJSONArray("details")
            if (detailsArray != null) {
                for (i in 0 until detailsArray.length()) {
                    details.add(detailsArray.getString(i))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing details", e)
        }
        return details
    }

    private fun sendProgress(progress: Int) {
        val intent = Intent(ACTION_SCAN_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Progress: $progress%")
    }

    private fun sendScanComplete(success: Boolean) {
        val intent = Intent(ACTION_SCAN_COMPLETE)
        sendBroadcast(intent)
        Log.d(TAG, "Scan complete: $success")
    }

    private fun sendScanResult(result: ScanResult) {
        val intent = Intent(ACTION_SCAN_RESULT).apply {
            putExtra(EXTRA_IS_THREAT, result.isThreat)
            putExtra(EXTRA_THREAT_TYPE, result.type)
            putExtra(EXTRA_MESSAGE, result.message)
            putExtra(EXTRA_DETAILS, result.details.toTypedArray())
            putExtra(EXTRA_CONFIDENCE, result.confidence)
            putExtra(EXTRA_TAGS_JSON, result.tagsJson)
        }
        sendBroadcast(intent)
        
        Log.d(TAG, "Result - Threat: ${result.isThreat}, Type: ${result.type}, Message: ${result.message}")
    }

    private fun extractText(node: AccessibilityNodeInfo, textList: MutableList<ScreenTextNode>) {
        try {
            if (node.text != null && node.text.isNotEmpty()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                textList.add(ScreenTextNode(node.text.toString(), rect))
            }
            
            if (node.contentDescription != null && node.contentDescription.isNotEmpty()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                textList.add(ScreenTextNode(node.contentDescription.toString(), rect))
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    extractText(child, textList)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text", e)
        }
    }

    override fun onInterrupt() {
        isScanning = false
        scanJob?.cancel()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isScanning = false
        scanJob?.cancel()
        serviceScope.cancel()
    }

    private fun buildTagsJson(responseJson: JSONObject, nodes: List<ScreenTextNode>): String {
        val tags = JSONArray()

        try {
            val apiTags = responseJson.optJSONArray("tags")
            if (apiTags != null && apiTags.length() > 0) {
                for (i in 0 until apiTags.length()) {
                    val tag = apiTags.optJSONObject(i) ?: continue
                    tags.put(JSONObject().apply {
                        put("label", tag.optString("label", "⚠️ THREAT"))
                        put("severity", tag.optString("severity", "warning"))
                        put("left", tag.optInt("left", 0))
                        put("top", tag.optInt("top", 0))
                    })
                }
                return tags.toString()
            }

            val detailPhrases = parseDetailsArray(responseJson)
                .map { it.lowercase(Locale.getDefault()) }

            if (detailPhrases.isNotEmpty()) {
                nodes.asSequence()
                    .filter { node ->
                        val nodeText = node.text.lowercase(Locale.getDefault())
                        detailPhrases.any { phrase -> phrase.isNotBlank() && nodeText.contains(phrase.take(30)) }
                    }
                    .distinctBy { "${it.bounds.left}-${it.bounds.top}-${it.bounds.right}-${it.bounds.bottom}" }
                    .take(8)
                    .forEach { node ->
                        tags.put(JSONObject().apply {
                            put("label", "⚠️ POTENTIAL THREAT")
                            put("severity", "warning")
                            put("left", node.bounds.left)
                            put("top", node.bounds.top)
                        })
                    }
            }

            if (tags.length() == 0 && responseJson.optBoolean("is_threat", false)) {
                nodes.take(3).forEach { node ->
                    tags.put(JSONObject().apply {
                        put("label", "⚠️ REVIEW")
                        put("severity", responseJson.optString("type", "warning"))
                        put("left", node.bounds.left)
                        put("top", node.bounds.top)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error building tags json", e)
        }

        return tags.toString()
    }

    data class ScreenTextNode(
        val text: String,
        val bounds: Rect,
    )

    data class ScanResult(
        val isThreat: Boolean,
        val type: String,
        val message: String,
        val details: List<String>,
        val confidence: Double,
        val tagsJson: String = "[]",
    )
}
