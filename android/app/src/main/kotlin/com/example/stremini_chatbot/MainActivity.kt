package com.example.stremini_chatbot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "stremini.chat.overlay"
    private val eventChannelName = "stremini.chat.overlay/events"
    private val keyboardChannelName = "stremini.keyboard"
    
    private var eventSink: EventChannel.EventSink? = null

    // REMOVED: private val eventReceiver = object : BroadcastReceiver() { ... }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // REMOVED: Scanner method channel ("com.example.stremini_chatbot")
        
        // Overlay channel for bubble controls
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "hasOverlayPermission" -> {
                    result.success(hasOverlayPermission())
                }
                "requestOverlayPermission" -> {
                    requestOverlayPermissionSafe()
                    result.success(true)
                }
                // REMOVED: "hasAccessibilityPermission"
                // REMOVED: "requestAccessibilityPermission"
                // REMOVED: "startScreenScan"
                "startOverlayService" -> {
                    startOverlayServiceSafe()
                    result.success(true)
                }
                "stopOverlayService" -> {
                    val intent = Intent(this, ChatOverlayService::class.java)
                    stopService(intent)
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }

        // Keyboard channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, keyboardChannelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "isKeyboardEnabled" -> {
                    result.success(isKeyboardEnabled())
                }
                "isKeyboardSelected" -> {
                    result.success(isKeyboardSelected())
                }
                "openKeyboardSettings" -> {
                    openKeyboardSettings()
                    result.success(true)
                }
                "showKeyboardPicker" -> {
                    showKeyboardPicker()
                    result.success(true)
                }
                "openKeyboardSettingsActivity" -> {
                    try {
                        val intent = Intent(this, KeyboardSettingsActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error opening keyboard settings", e)
                        result.error("ERROR", "Failed to open keyboard settings: ${e.message}", null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        // Event channel for async updates
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                }
                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            }
        )
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayPermissionSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Overlay setting error", e)
            Toast.makeText(this, "Error opening settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // REMOVED: private fun isAccessibilityServiceEnabled(): Boolean { ... }
    // REMOVED: private fun requestAccessibilityPermissionSafe() { ... }

    private fun startOverlayServiceSafe() {
        try {
            if (!hasOverlayPermission()) {
                requestOverlayPermissionSafe()
                return
            }
            val intent = Intent(this, ChatOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Floating bubble activated", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Overlay start error", e)
            Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // REMOVED: private fun startScreenScan() { ... }

    // Keyboard-related methods
    private fun isKeyboardEnabled(): Boolean {
        return try {
            val imeManager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            val enabledInputMethods = imeManager?.enabledInputMethodList ?: return false
            val packageName = packageName
            
            enabledInputMethods.any { 
                it.packageName == packageName 
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking keyboard enabled", e)
            false
        }
    }

    private fun isKeyboardSelected(): Boolean {
        return try {
            val currentInputMethod = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            
            currentInputMethod?.contains(packageName) == true
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking keyboard selected", e)
            false
        }
    }

    private fun openKeyboardSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            
            Toast.makeText(
                this,
                "Find 'Stremini AI Keyboard' and enable it",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening keyboard settings", e)
            Toast.makeText(this, "Error opening settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showKeyboardPicker() {
        try {
            val imeManager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imeManager?.showInputMethodPicker()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing keyboard picker", e)
            Toast.makeText(this, "Error showing picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // REMOVED: Receiver registration logic
    }

    override fun onPause() {
        super.onPause()
        // REMOVED: Receiver unregistration logic
    }
}
