import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:stremini_chatbot/screens/chat_screen.dart';
import 'package:stremini_chatbot/widgets/draggable_chat_icon.dart';

import '../providers/chat_window_state_provider.dart';
import '../providers/scanner_provider.dart';

class ChatOverlayManager extends ConsumerStatefulWidget {
  final Widget child;
  const ChatOverlayManager({super.key, required this.child});

  @override
  ConsumerState<ChatOverlayManager> createState() => _ChatOverlayManagerState();
}

class _ChatOverlayManagerState extends ConsumerState<ChatOverlayManager> {
  // Initial position for the chat head
  Offset _bubblePosition = const Offset(20, 200);

  void updatePosition(Offset newPosition) {
    setState(() {
      _bubblePosition = newPosition;
    });
  }

  void cycleOverlayMode() {
    final notifier = ref.read(chatWindowStateProvider.notifier);
    final currentMode = ref.read(chatWindowStateProvider).overlayMode;

    // Toggle between icon and radial menu
    if (currentMode == "icon") {
      notifier.setMode("radial");
    } else if (currentMode == "radial") {
      notifier.setMode("icon");
    }
  }

  void openMaximizedChat() {
    ref.read(chatWindowStateProvider.notifier).setMode("maximized");
  }

  void closeMaximizedChat() {
    ref.read(chatWindowStateProvider.notifier).setMode("radial");
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(chatWindowStateProvider);
    final scannerState = ref.watch(scannerStateProvider);
    final isMaximized = state.overlayMode == "maximized";

    return Stack(
      textDirection: TextDirection.ltr,
      children: [
        // 1. The main application content (The App UI)
        widget.child,

        // 2. The Floating Chat Icon Layer
        // IgnorePointer wrapping with ignoring: true means touches pass through
        // by default, but the DraggableChatIcon will handle its own gestures
        if (!isMaximized)
          IgnorePointer(
            ignoring: true,
            child: DraggableChatIcon(
              position: _bubblePosition,
              onDragEnd: updatePosition,
              overlayMode: state.overlayMode,
              onTapMain: cycleOverlayMode,
              onOpenApp: openMaximizedChat,
              isScannerActive: scannerState.isActive,
            ),
          ),

        // 3. The Maximized Chat Window Layer
        if (isMaximized) _buildMaximizedChat(context),
      ],
    );
  }

  Widget _buildMaximizedChat(BuildContext context) {
    return Material(
      color: Colors.black.withOpacity(0.95),
      child: Stack(
        children: [
          const ChatScreen(),
          Positioned(
            top: MediaQuery.of(context).padding.top + 10,
            right: 10,
            child: IconButton(
              icon: const Icon(Icons.close, color: Colors.white, size: 30),
              onPressed: closeMaximizedChat,
            ),
          ),
        ],
      ),
    );
  }
}
