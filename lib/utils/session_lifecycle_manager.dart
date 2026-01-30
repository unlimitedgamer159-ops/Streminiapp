import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/api_service.dart';

class SessionLifecycleManager extends ConsumerStatefulWidget {
  final Widget child;

  const SessionLifecycleManager({
    super.key,
    required this.child,
  });

  @override
  ConsumerState<SessionLifecycleManager> createState() => _SessionLifecycleManagerState();
}

class _SessionLifecycleManagerState extends ConsumerState<SessionLifecycleManager>
    with WidgetsBindingObserver {
  
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _clearSessionOnDispose();
    super.dispose();
  }

  Future<void> _clearSessionOnDispose() async {
    try {
      await ref.read(apiServiceProvider).clearSession();
    } catch (e) {
      debugPrint('Error clearing session on dispose: $e');
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    
    if (state == AppLifecycleState.detached) {
      ref.read(apiServiceProvider).clearSession();
    }
  }

  @override
  Widget build(BuildContext context) {
    return widget.child;
  }
}
