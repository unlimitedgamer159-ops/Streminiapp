import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/api_service.dart';

// State provider for floating scanner
final floatingScannerProvider = NotifierProvider<FloatingScannerNotifier, FloatingScannerState>(
  FloatingScannerNotifier.new,
);

class FloatingScannerState {
  final bool isVisible;
  final bool isScanning;
  final String? scannedText;
  final SecurityScanResult? scanResult;
  final String? error;

  FloatingScannerState({
    this.isVisible = false,
    this.isScanning = false,
    this.scannedText,
    this.scanResult,
    this.error,
  });

  FloatingScannerState copyWith({
    bool? isVisible,
    bool? isScanning,
    String? scannedText,
    SecurityScanResult? scanResult,
    String? error,
  }) {
    return FloatingScannerState(
      isVisible: isVisible ?? this.isVisible,
      isScanning: isScanning ?? this.isScanning,
      scannedText: scannedText ?? this.scannedText,
      scanResult: scanResult ?? this.scanResult,
      error: error ?? this.error,
    );
  }
}

class FloatingScannerNotifier extends Notifier<FloatingScannerState> {
  static const MethodChannel _channel = MethodChannel('stremini.chat.overlay');

  @override
  FloatingScannerState build() {
    return FloatingScannerState();
  }

  void show() {
    state = state.copyWith(isVisible: true, error: null);
    _startScreenScan();
  }

  void hide() {
    state = FloatingScannerState();
  }

  Future<void> _startScreenScan() async {
    state = state.copyWith(isScanning: true);
    
    try {
      // Check if accessibility permission is granted
      final bool? hasPermission = await _channel.invokeMethod<bool>('hasAccessibilityPermission');
      
      if (hasPermission != true) {
        state = state.copyWith(
          isScanning: false,
          error: 'Accessibility permission is required. Please enable it in settings.',
        );
        return;
      }

      // Trigger screen scan
      await _channel.invokeMethod('startScreenScan');
    } catch (e) {
      state = state.copyWith(
        isScanning: false,
        error: 'Failed to start scan: $e',
      );
    }
  }

  Future<void> processScanResult(String scannedText) async {
    state = state.copyWith(scannedText: scannedText);
    
    try {
      final apiService = ref.read(apiServiceProvider);
      final result = await apiService.scanContent(scannedText);
      
      state = state.copyWith(
        isScanning: false,
        scanResult: result,
      );
    } catch (e) {
      state = state.copyWith(
        isScanning: false,
        error: 'Failed to analyze content: $e',
      );
    }
  }

  void clearError() {
    state = state.copyWith(error: null);
  }
}

// Floating Scanner Widget
class FloatingScanner extends ConsumerWidget {
  const FloatingScanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(floatingScannerProvider);
    final notifier = ref.read(floatingScannerProvider.notifier);

    if (!state.isVisible) return const SizedBox.shrink();

    return Material(
      color: Colors.black.withOpacity(0.95),
      child: SafeArea(
        child: Stack(
          children: [
            // Main content
            Column(
              children: [
                // Header
                _buildHeader(notifier),
                
                // Content
                Expanded(
                  child: _buildContent(state, notifier, context),
                ),
              ],
            ),
            
            // Close button
            Positioned(
              top: 16,
              right: 16,
              child: IconButton(
                icon: const Icon(Icons.close, color: Colors.white, size: 30),
                onPressed: () => notifier.hide(),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(FloatingScannerNotifier notifier) {
    return Container(
      padding: const EdgeInsets.all(20),
      child: Row(
        children: [
          Container(
            width: 48,
            height: 48,
            decoration: BoxDecoration(
              color: Colors.purple.withOpacity(0.2),
              borderRadius: BorderRadius.circular(12),
            ),
            child: const Icon(Icons.scanner, color: Colors.purple),
          ),
          const SizedBox(width: 16),
          const Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Screen Scanner',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 20,
                  fontWeight: FontWeight.bold,
                ),
              ),
              Text(
                'Detecting scams & fraud',
                style: TextStyle(
                  color: Colors.grey,
                  fontSize: 14,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildContent(FloatingScannerState state, FloatingScannerNotifier notifier, BuildContext context) {
    if (state.error != null) {
      return _buildError(state.error!, notifier, context);
    }

    if (state.isScanning) {
      return _buildScanning();
    }

    if (state.scanResult != null) {
      return _buildResults(state.scanResult!);
    }

    return _buildWaiting();
  }

  Widget _buildScanning() {
    return const Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          SizedBox(
            width: 80,
            height: 80,
            child: CircularProgressIndicator(
              strokeWidth: 6,
              valueColor: AlwaysStoppedAnimation<Color>(Colors.purple),
            ),
          ),
          SizedBox(height: 24),
          Text(
            'Scanning screen...',
            style: TextStyle(color: Colors.white, fontSize: 18),
          ),
          SizedBox(height: 8),
          Text(
            'Analyzing content for threats',
            style: TextStyle(color: Colors.grey, fontSize: 14),
          ),
        ],
      ),
    );
  }

  Widget _buildWaiting() {
    return const Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.scanner, color: Colors.purple, size: 80),
          SizedBox(height: 24),
          Text(
            'Ready to scan',
            style: TextStyle(color: Colors.white, fontSize: 18),
          ),
        ],
      ),
    );
  }

  Widget _buildError(String error, FloatingScannerNotifier notifier, BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error_outline, color: Colors.orange, size: 80),
            const SizedBox(height: 24),
            Text(
              error,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white, fontSize: 16),
            ),
            const SizedBox(height: 24),
            if (error.contains('Accessibility'))
              ElevatedButton.icon(
                onPressed: () async {
                  const channel = MethodChannel('stremini.chat.overlay');
                  await channel.invokeMethod('requestAccessibilityPermission');
                },
                icon: const Icon(Icons.settings),
                label: const Text('Open Settings'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.purple,
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                ),
              )
            else
              ElevatedButton(
                onPressed: () => notifier.clearError(),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.purple,
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                ),
                child: const Text('Try Again'),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildResults(SecurityScanResult result) {
    final bool isSafe = result.riskLevel != 'danger' && result.riskLevel != 'warning';
    final Color bannerStart = isSafe ? const Color(0xFF1A3826) : const Color(0xFF38261A);
    final Color bannerEnd = isSafe ? const Color(0xFF0F251B) : const Color(0xFF251B0F);
    final Color bannerBorder = isSafe ? const Color(0xFF2D5C43) : const Color(0xFF5C432D);
    final Color iconColor = isSafe ? const Color(0xFF6DD58C) : const Color(0xFFFFD580);
    final Color titleColor = Colors.white;
    final Color subTextColor = Colors.white70;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Status card
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [bannerStart, bannerEnd],
                begin: Alignment.centerLeft,
                end: Alignment.centerRight,
              ),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: bannerBorder, width: 1.5),
            ),
            child: Row(
              children: [
                Icon(
                  result.isSafe ? Icons.shield_outlined : Icons.shield,
                  color: iconColor,
                  size: 36,
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        result.isSafe ? 'Safe: No Threat Detected' : 'Warning: Potential Threats',
                        style: TextStyle(
                          color: titleColor,
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      Text(
                        result.riskLevel.toUpperCase(),
                        style: TextStyle(
                          color: subTextColor,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),

          // Tags
          if (result.tags.isNotEmpty) ...[
            const Text(
              'Detected Issues',
              style: TextStyle(
                color: Colors.white,
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: result.tags.map((tag) {
                final label = tag.trim().toLowerCase();
                final isTagSafe = label == 'safe';
                final isDangerous = label == 'scam' || label == 'danger' || label == 'threat';
                final Color backgroundColor = isTagSafe
                    ? const Color(0xFF0F291E)
                    : isDangerous
                        ? const Color(0xFF2A1215)
                        : const Color(0xFF2A2412);
                final Color borderColor = isTagSafe
                    ? const Color(0xFF1B4D36)
                    : isDangerous
                        ? const Color(0xFF5C2B2F)
                        : const Color(0xFF5C4D2B);
                final Color textColor = isTagSafe
                    ? const Color(0xFF6DD58C)
                    : isDangerous
                        ? const Color(0xFFFF8080)
                        : const Color(0xFFFFD580);
                final IconData icon = isTagSafe ? Icons.shield_outlined : Icons.shield;
                final String tagText = isTagSafe ? 'Safe: No Threat Detected' : tag;

                return Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  decoration: BoxDecoration(
                    color: backgroundColor,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: borderColor, width: 1),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(icon, color: textColor, size: 14),
                      const SizedBox(width: 4),
                      Text(
                        tagText,
                        style: TextStyle(color: textColor, fontSize: 12, fontWeight: FontWeight.w600),
                      ),
                    ],
                  ),
                );
              }).toList(),
            ),
            const SizedBox(height: 24),
          ],

          // Analysis
          const Text(
            'Analysis',
            style: TextStyle(
              color: Colors.white,
              fontSize: 18,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 12),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.grey[900],
              borderRadius: BorderRadius.circular(12),
            ),
            child: Text(
              result.analysis,
              style: const TextStyle(color: Colors.white, fontSize: 14, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }
}
