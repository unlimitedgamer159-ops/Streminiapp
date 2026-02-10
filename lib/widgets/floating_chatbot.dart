import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/api_service.dart';

// State provider for floating chatbot
final floatingChatbotProvider = NotifierProvider<FloatingChatbotNotifier, FloatingChatbotState>(
  FloatingChatbotNotifier.new,
);

class FloatingChatbotState {
  final bool isVisible;
  final bool isFullscreen;
  final Offset position;
  final List<ChatMessage> messages;
  final bool isLoading;

  FloatingChatbotState({
    this.isVisible = false,
    this.isFullscreen = false,
    this.position = const Offset(20, 100),
    this.messages = const [],
    this.isLoading = false,
  });

  FloatingChatbotState copyWith({
    bool? isVisible,
    bool? isFullscreen,
    Offset? position,
    List<ChatMessage>? messages,
    bool? isLoading,
  }) {
    return FloatingChatbotState(
      isVisible: isVisible ?? this.isVisible,
      isFullscreen: isFullscreen ?? this.isFullscreen,
      position: position ?? this.position,
      messages: messages ?? this.messages,
      isLoading: isLoading ?? this.isLoading,
    );
  }
}

class ChatMessage {
  final String text;
  final bool isUser;
  final DateTime timestamp;

  ChatMessage({
    required this.text,
    required this.isUser,
    required this.timestamp,
  });
}

class FloatingChatbotNotifier extends Notifier<FloatingChatbotState> {
  @override
  FloatingChatbotState build() {
    return FloatingChatbotState(
      messages: [
        // Initial welcome message matching the web demo
        ChatMessage(
          text: "Hey there! I'm Stremini - your AI assistant & digital bodyguard.",
          isUser: false,
          timestamp: DateTime.now(),
        ),
        ChatMessage(
          text: "How can I help you today?",
          isUser: false,
          timestamp: DateTime.now(),
        ),
      ]
    );
  }

  void show() {
    state = state.copyWith(isVisible: true);
  }

  void hide() {
    state = state.copyWith(isVisible: false, isFullscreen: false);
  }

  void toggleFullscreen() {
    state = state.copyWith(isFullscreen: !state.isFullscreen);
  }

  void updatePosition(Offset newPosition) {
    state = state.copyWith(position: newPosition);
  }

  void addMessage(String text, bool isUser) {
    final newMessage = ChatMessage(
      text: text,
      isUser: isUser,
      timestamp: DateTime.now(),
    );
    state = state.copyWith(
      messages: [...state.messages, newMessage],
    );
  }

  void setLoading(bool loading) {
    state = state.copyWith(isLoading: loading);
  }

  void clearMessages() {
    state = state.copyWith(messages: []);
  }
}

// Floating Chatbot Widget
class FloatingChatbot extends ConsumerStatefulWidget {
  const FloatingChatbot({super.key});

  @override
  ConsumerState<FloatingChatbot> createState() => _FloatingChatbotState();
}

class _FloatingChatbotState extends ConsumerState<FloatingChatbot> {
  final TextEditingController _controller = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  @override
  void dispose() {
    _controller.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _scrollToBottom() {
    Future.delayed(const Duration(milliseconds: 100), () {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
        );
      }
    });
  }

  void _sendMessage() async {
    final text = _controller.text.trim();
    if (text.isEmpty) return;

    final notifier = ref.read(floatingChatbotProvider.notifier);
    final currentState = ref.read(floatingChatbotProvider);
    final apiService = ref.read(apiServiceProvider);

    // 1. Add user message locally
    notifier.addMessage(text, true);
    _controller.clear();
    _scrollToBottom();

    // 2. Prepare History for Backend (Gemini Format)
    // Map internal ChatMessage to { role: 'user'|'model', parts: [{text: ...}] }
    final history = currentState.messages.map((msg) => {
      "role": msg.isUser ? "user" : "model",
      "parts": [
        { "text": msg.text }
      ]
    }).toList();

    // 3. Get AI response
    notifier.setLoading(true);
    try {
      // Pass the history along with the new message
      final response = await apiService.sendMessage(text, history: history);
      notifier.addMessage(response, false);
    } catch (e) {
      notifier.addMessage("Error: $e", false);
    } finally {
      notifier.setLoading(false);
      _scrollToBottom();
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(floatingChatbotProvider);
    final notifier = ref.read(floatingChatbotProvider.notifier);

    if (!state.isVisible) return const SizedBox.shrink();

    final screenSize = MediaQuery.of(context).size;

    if (state.isFullscreen) {
      return _buildFullscreenChat(context);
    }

    // Mini floating window
    return Positioned(
      left: state.position.dx,
      top: state.position.dy,
      child: GestureDetector(
        onPanUpdate: (details) {
          notifier.updatePosition(
            Offset(
              (state.position.dx + details.delta.dx).clamp(0, screenSize.width - 320),
              (state.position.dy + details.delta.dy).clamp(0, screenSize.height - 400),
            ),
          );
        },
        child: Material(
          elevation: 8,
          borderRadius: BorderRadius.circular(16),
          child: Container(
            width: 320,
            height: 400,
            decoration: BoxDecoration(
              color: Colors.black.withOpacity(0.95),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.blue, width: 2),
              boxShadow: [
                 BoxShadow(color: Colors.blue.withOpacity(0.2), blurRadius: 10, spreadRadius: 2)
              ]
            ),
            child: Column(
              children: [
                // Header with controls
                _buildHeader(notifier),
                // Messages
                Expanded(
                  child: _buildMessageList(state),
                ),
                // Input
                _buildInput(),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildHeader(FloatingChatbotNotifier notifier) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.blue.withOpacity(0.2),
        borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
      ),
      child: Row(
        children: [
          const Icon(Icons.smart_toy, color: Colors.blue, size: 20),
          const SizedBox(width: 8),
          const Text(
            'Stremini AI',
            style: TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.bold,
              fontSize: 14,
            ),
          ),
          const Spacer(),
          IconButton(
            icon: const Icon(Icons.fullscreen, color: Colors.white, size: 20),
            onPressed: () => notifier.toggleFullscreen(),
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(),
          ),
          const SizedBox(width: 8),
          IconButton(
            icon: const Icon(Icons.close, color: Colors.white, size: 20),
            onPressed: () => notifier.hide(),
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(),
          ),
        ],
      ),
    );
  }

  Widget _buildMessageList(FloatingChatbotState state) {
    return ListView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.all(12),
      itemCount: state.messages.length + (state.isLoading ? 1 : 0),
      itemBuilder: (context, index) {
        if (index == state.messages.length && state.isLoading) {
          return const Padding(
            padding: EdgeInsets.all(8.0),
            child: Row(
              children: [
                 SizedBox(
                  width: 16, 
                  height: 16, 
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.blue)
                ),
                SizedBox(width: 8),
                Text(
                  'AI is thinking...',
                  style: TextStyle(color: Colors.grey, fontSize: 12),
                ),
              ],
            ),
          );
        }

        final message = state.messages[index];
        return Align(
          alignment: message.isUser ? Alignment.centerRight : Alignment.centerLeft,
          child: Container(
            margin: const EdgeInsets.only(bottom: 8),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            constraints: const BoxConstraints(maxWidth: 240),
            decoration: BoxDecoration(
              color: message.isUser ? Colors.blue : Colors.grey[800],
              borderRadius: BorderRadius.only(
                topLeft: const Radius.circular(12),
                topRight: const Radius.circular(12),
                bottomLeft: message.isUser ? const Radius.circular(12) : Radius.zero,
                bottomRight: message.isUser ? Radius.zero : const Radius.circular(12),
              ),
            ),
            child: Text(
              message.text,
              style: const TextStyle(color: Colors.white, fontSize: 13),
            ),
          ),
        );
      },
    );
  }

  Widget _buildInput() {
    return Container(
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: Colors.grey[900],
        borderRadius: const BorderRadius.vertical(bottom: Radius.circular(16)),
        border: Border(top: BorderSide(color: Colors.white.withOpacity(0.1)))
      ),
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.mic, color: Colors.blue, size: 20),
            onPressed: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Voice input coming soon')),
              );
            },
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: _controller,
              style: const TextStyle(color: Colors.white, fontSize: 13),
              decoration: InputDecoration(
                hintText: 'Ask Stremini...',
                hintStyle: TextStyle(color: Colors.grey[600], fontSize: 13),
                border: InputBorder.none,
                contentPadding: const EdgeInsets.symmetric(horizontal: 8),
              ),
              onSubmitted: (_) => _sendMessage(),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.send, color: Colors.blue, size: 20),
            onPressed: _sendMessage,
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(),
          ),
        ],
      ),
    );
  }

  Widget _buildFullscreenChat(BuildContext context) {
    final notifier = ref.read(floatingChatbotProvider.notifier);
    final state = ref.watch(floatingChatbotProvider);

    return Material(
      color: Colors.black.withOpacity(0.95),
      child: SafeArea(
        child: Column(
          children: [
            // Header
            Container(
              padding: const EdgeInsets.all(16),
              decoration: const BoxDecoration(
                border: Border(bottom: BorderSide(color: Colors.grey, width: 0.5))
              ),
              child: Row(
                children: [
                  const Icon(Icons.smart_toy, color: Colors.blue),
                  const SizedBox(width: 12),
                  const Text(
                    'Stremini AI',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const Spacer(),
                  IconButton(
                    icon: const Icon(Icons.fullscreen_exit, color: Colors.white),
                    onPressed: () => notifier.toggleFullscreen(),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close, color: Colors.white),
                    onPressed: () => notifier.hide(),
                  ),
                ],
              ),
            ),
            
            // Messages
            Expanded(
              child: _buildMessageList(state),
            ),
            // Input
            _buildInput(),
          ],
        ),
      ),
    );
  }
}
