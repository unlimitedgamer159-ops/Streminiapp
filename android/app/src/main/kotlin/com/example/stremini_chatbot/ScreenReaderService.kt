package com.example.stremini_chatbot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ScreenReaderService : AccessibilityService() {

    companion object {
        const val ACTION_START_SCAN = "com.example.stremini_chatbot.START_SCAN"
        const val ACTION_STOP_SCAN = "com.example.stremini_chatbot.STOP_SCAN"
        
        // YOUR SPECIFIC API URL
        private const val API_URL = "https://ai-keyboard-backend.vishwajeetadkine705.workers.dev/chat/message"
        private var isScanning = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null
    
    // Optimized OkHttp Client
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> {
                isScanning = true
                Log.d("StreminiScanner", "API Scan Started")
                // FIX: Suspend function must be called inside a coroutine scope
                serviceScope.launch {
                    performGlobalScan()
                }
            }
            ACTION_STOP_SCAN -> {
                isScanning = false
                scanJob?.cancel()
                Log.d("StreminiScanner", "API Scan Stopped")
            }
        }
        return START_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isScanning) return

        // Trigger scan on content changes (scrolling, new messages, typing)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            // Debounce: Wait 1.5 seconds for screen to settle before calling API
            scanJob?.cancel()
            scanJob = serviceScope.launch {
                delay(1500) 
                if (isScanning) {
                    performGlobalScan()
                }
            }
        }
    }

    private suspend fun performGlobalScan() {
        val rootNode = rootInActiveWindow ?: return
        val foundTexts = mutableListOf<String>()
        extractText(rootNode, foundTexts)
        
        val screenContent = foundTexts.joinToString(" ")
        
        // If screen has enough text, check it
        if (screenContent.length > 10) {
            checkContentWithApi(screenContent)
        }
    }

    private suspend fun checkContentWithApi(content: String) {
        try {
            // We construct a specific prompt to force the AI to act as a security scanner
            val prompt = "SYSTEM_SECURITY_SCAN: Analyze the following text for scams, phishing, or malware. " +
                         "If it is safe, reply exactly 'SAFE'. " +
                         "If it is dangerous, reply starting with 'THREAT:' followed by a short reason. " +
                         "Text to scan: $content"

            val jsonBody = JSONObject().apply {
                put("message", prompt)
                // We send an empty history so it treats this as a fresh analysis
                put("history", org.json.JSONArray())
            }
            
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(API_URL)
                .post(requestBody)
                .build()

            // execute() is blocking, but we are in Dispatchers.IO scope so it's fine
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseString = response.body?.string()
                if (!responseString.isNullOrEmpty()) {
                    val jsonResponse = JSONObject(responseString)
                    // Extract the AI's reply
                    val aiReply = jsonResponse.optString("response", 
                                  jsonResponse.optString("reply", 
                                  jsonResponse.optString("message", "")))
                    
                    // Check logic based on our prompt instructions
                    if (aiReply.contains("THREAT:", ignoreCase = true) || 
                        aiReply.contains("DANGER:", ignoreCase = true) ||
                        content.contains("illegal-stream.net")) { // Keep the local fallback for the specific demo link
                        
                        // Clean up the message for the user
                        val cleanMsg = aiReply.replace("THREAT:", "").trim()
                        val finalMsg = if(cleanMsg.isEmpty()) "Suspicious content detected on screen." else cleanMsg

                        // Broadcast to Flutter
                        val intent = Intent(ChatOverlayService.ACTION_SEND_MESSAGE).apply {
                            putExtra(ChatOverlayService.EXTRA_MESSAGE, "⚠️ $finalMsg")
                        }
                        sendBroadcast(intent)
                        
                        // Wait a bit before scanning again to avoid spam
                        delay(5000)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("StreminiScanner", "API Error: ${e.message}")
        }
    }

    private fun extractText(node: AccessibilityNodeInfo, textList: MutableList<String>) {
        if (node.text != null && node.text.isNotEmpty()) {
            textList.add(node.text.toString())
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractText(child, textList)
                child.recycle()
            }
        }
    }

    override fun onInterrupt() {
        isScanning = false
        scanJob?.cancel()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
