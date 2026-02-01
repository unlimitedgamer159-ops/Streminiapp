import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/message_model.dart';
import '../services/api_service.dart';

class ChatNotifier extends AsyncNotifier<List<Message>> {
  static const String _initialGreetingId = 'initial_greeting';

  @override
  FutureOr<List<Message>> build() {
    return [
      Message(
        id: _initialGreetingId,
        text: "Hello! I'm Stremini AI. How can I help you today?",
        type: MessageType.bot,
        timestamp: DateTime.now(),
      )
    ];
  }

  // FIXED: Increased limit to 100 messages (approx 50 conversation turns)
  List<Map<String, dynamic>> _getHistory() {
    final currentMessages = state.value ?? [];
    List<Map<String, dynamic>> history = [];

    for (var msg in currentMessages) {
      // Skip greeting, typing indicators, and error messages
      if (msg.id == _initialGreetingId || 
          msg.type == MessageType.typing || 
          msg.text.startsWith('❌') || 
          msg.text.startsWith('⚠️')) {
        continue;
      }

      String role = msg.type == MessageType.user ? 'user' : 'assistant';
      history.add({
        "role": role,
        "content": msg.text
      });
    }

    // Keep the last 100 messages for context
    if (history.length > 100) {
      history = history.sublist(history.length - 100);
    }
    return history;
  }

  Future<void> sendMessage(String text, {String? attachment, String? mimeType, String? fileName}) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty && attachment == null) return;

    final displayCheck = trimmed.isEmpty ? "Sent an attachment: $fileName" : trimmed;

    final userMessage = Message(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      text: displayCheck,
      type: MessageType.user,
      timestamp: DateTime.now(),
    );

    // 1. Capture history BEFORE adding new message (So we send: Past Context + New Question)
    final history = _getHistory();

    // 2. Update UI
    final current = state.value ?? <Message>[];
    final filtered = current.where((m) => m.id != _initialGreetingId).toList();
    state = AsyncValue.data([...filtered, userMessage]);

    addTypingIndicator();

    try {
      final api = ref.read(apiServiceProvider);
      
      // 3. Send Message WITH Long History
      final reply = await api.sendMessage(
        trimmed, 
        attachment: attachment, 
        mimeType: mimeType, 
        fileName: fileName,
        history: history 
      );

      removeTypingIndicator();

      final botMessage = Message(
        id: DateTime.now().millisecondsSinceEpoch.toString(),
        text: reply,
        type: MessageType.bot,
        timestamp: DateTime.now(),
      );

      state = AsyncValue.data([...(state.value ?? []), botMessage]);
    } catch (e) {
      removeTypingIndicator();
      state = AsyncValue.data([...(state.value ?? []), 
        Message(
          id: DateTime.now().toString(), 
          text: '⚠️ Error: $e', 
          type: MessageType.bot, 
          timestamp: DateTime.now()
        )
      ]);
    }
  }

  void addTypingIndicator() {
    final current = state.value ?? <Message>[];
    if (current.any((m) => m.type == MessageType.typing)) return;
    state = AsyncValue.data([...current, Message(id: 'typing', text: '...', type: MessageType.typing, timestamp: DateTime.now())]);
  }

  void removeTypingIndicator() {
    final current = state.value ?? <Message>[];
    state = AsyncValue.data(current.where((m) => m.type != MessageType.typing).toList());
  }

  Future<void> clearChat() async {
    state = AsyncValue.data([
      Message(
        id: _initialGreetingId,
        text: "Hello! I'm Stremini AI. How can I help you today?",
        type: MessageType.bot,
        timestamp: DateTime.now(),
      )
    ]);
  }
}

final chatNotifierProvider = AsyncNotifierProvider<ChatNotifier, List<Message>>(ChatNotifier.new);
