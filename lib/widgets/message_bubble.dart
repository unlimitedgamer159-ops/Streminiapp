import 'package:flutter/material.dart';
import '../models/message_model.dart';

class MessageBubble extends StatelessWidget {
  final Message message;

  const MessageBubble({
    super.key,
    required this.message,
  });

  @override
  Widget build(BuildContext context) {
    if (message.type == MessageType.typing) {
      return _buildTypingIndicator();
    }

    final isUser = message.type == MessageType.user;

    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4, horizontal: 10),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: BoxDecoration(
          color: isUser ? Colors.grey[800] : Colors.transparent,
          borderRadius: BorderRadius.circular(20),
          border: isUser ? null : Border.all(color: Colors.grey.withOpacity(0.3)), // Optional border for bot
        ),
        constraints: BoxConstraints(
          maxWidth: isUser
              ? MediaQuery.of(context).size.width * 0.75
              : MediaQuery.of(context).size.width * 0.95, // Wider for bot code blocks
        ),
        // KEY CHANGE: SelectableText makes it copyable
        child: SelectableText(
          message.text,
          style: const TextStyle(
            color: Colors.white,
            fontSize: 16,
            height: 1.5,
          ),
          cursorColor: Colors.blue,
          toolbarOptions: const ToolbarOptions(
            copy: true,
            selectAll: true,
          ),
        ),
      ),
    );
  }

  Widget _buildTypingIndicator() {
    // Keep your existing typing indicator logic
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        padding: const EdgeInsets.all(16),
        child: const Text("...", style: TextStyle(color: Colors.white, fontSize: 24)),
      ),
    );
  }
}
