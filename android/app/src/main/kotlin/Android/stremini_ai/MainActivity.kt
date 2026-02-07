package Android.stremini_ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
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

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            
            if (action == ScreenReaderService.ACTION_SCAN_COMPLETE) {
                val scannedText = intent.getStringExtra(ScreenReaderService.EXTRA_SCANNED_TEXT)
                val error = intent.getStringExtra("error")
                
                if (error != null) {
                    eventSink?.success(mapOf(
                        "action" to "scan_error",
                        "error" to error
                    ))
                } else {
                    eventSink?.success(mapOf(
                        "action" to "scan_complete",
                        "text" to (scannedText ?: "")
                    ))
                }
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // Scanner method channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "Android.stremini_ai").setMethodCallHandler { call, result ->
            when (call.method) {
                "startScanner" -> {
                    result.success(true)
                }
                "stopScanner" -> {
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
        
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
                "hasAccessibilityPermission" -> {
                    result.success(isAccessibilityServiceEnabled())
                }
                "requestAccessibilityPermission" -> {
                    requestAccessibilityPermissionSafe()
                    result.success(true)
                }
                "startScreenScan" -> {
                    if (isAccessibilityServiceEnabled()) {
                        startScreenScan()
                        result.success(true)
                    } else {
                        result.error("NO_PERMISSION", "Accessibility service not enabled", null)
                    }
                }
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${ScreenReaderService::class.java.canonicalName}"
        val settingValue = Settings.Secure.getString(
            contentResolver, 
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(settingValue)
        while (splitter.hasNext()) {
            if (splitter.next().equals(serviceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun requestAccessibilityPermissionSafe() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(
                this, 
                "Please find and enable 'Stremini Screen Scanner'", 
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening accessibility settings", e)
            Toast.makeText(this, "Error opening settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun startScreenScan() {
        try {
            val intent = Intent(this, ScreenReaderService::class.java).apply {
                action = ScreenReaderService.ACTION_START_SCAN
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting scan", e)
            Toast.makeText(this, "Error starting scan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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
        try {
            val filter = IntentFilter(ScreenReaderService.ACTION_SCAN_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(eventReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(eventReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error registering receiver", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try { 
            unregisterReceiver(eventReceiver) 
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver", e)
        }
    }
}
