import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'core/theme/app_theme.dart';
import 'screens/home/home_screen.dart';
import 'providers/scanner_provider.dart';

final globalContainerProvider = Provider<ProviderContainer?>((ref) => null);

ProviderContainer? globalContainer;
 
void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ProviderScope(
      child: _AppWithContainer(),
    );
  }
}

class _AppWithContainer extends ConsumerStatefulWidget {
  const _AppWithContainer();

  @override
  ConsumerState<_AppWithContainer> createState() => _AppWithContainerState();
}

class _AppWithContainerState extends ConsumerState<_AppWithContainer> {
  static const platform = MethodChannel('com.example.stremini_chatbot');

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (globalContainer == null) {
      globalContainer = ProviderScope.containerOf(context);
      _setupScannerListeners();
    }
  }

  void _setupScannerListeners() {
    if (globalContainer == null) return;

    platform.setMethodCallHandler((call) async {
      final notifier = globalContainer!.read(scannerStateProvider.notifier);

      switch (call.method) {
        case 'startScanner':
          print('Scanner button clicked - starting scanner');
          await notifier.startScanning();
          return true;
        case 'stopScanner':
          print('Scanner button clicked - stopping scanner');
          await notifier.stopScanning();
          return false;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Stremini AI',
      theme: AppTheme.darkTheme,
      home: const HomeScreen(),
    );
  }
}
