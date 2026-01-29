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

  // Updated signature to accept attachment
  Future<void> sendMessage(String text, {String? attachment, String? mimeType, String? fileName}) async {
    final trimmed = text.trim();
    // Allow empty text if sending a file
    if (trimmed.isEmpty && attachment == null) return;

    final displayCheck = trimmed.isEmpty ? "Sent an attachment: $fileName" : trimmed;

    final userMessage = Message(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      text: displayCheck,
      type: MessageType.user,
      timestamp: DateTime.now(),
    );

    final current = state.value ?? <Message>[];
    final filtered = current.where((m) => m.id != _initialGreetingId).toList();
    state = AsyncValue.data([...filtered, userMessage]);

    addTypingIndicator();

    try {
      final api = ref.read(apiServiceProvider);
      // Pass attachment data
      final reply = await api.sendMessage(trimmed, attachment: attachment, mimeType: mimeType, fileName: fileName);

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
          text: 'Error: $e', 
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
}

final chatNotifierProvider = AsyncNotifierProvider<ChatNotifier, List<Message>>(ChatNotifier.new);
