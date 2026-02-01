import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:file_picker/file_picker.dart';
import 'package:mime/mime.dart';
import '../providers/chat_provider.dart';
import '../models/message_model.dart';

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key});

  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final TextEditingController _controller = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final FocusNode _focusNode = FocusNode();
  
  // Attachment State
  File? _selectedFile;
  String? _base64File;
  String? _mimeType;
  String? _fileName;

  @override
  void dispose() {
    _controller.dispose();
    _scrollController.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  // File Picking Logic
  Future<void> _pickAttachment() async {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.grey[900],
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => Container(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.image, color: Colors.blue),
              title: const Text('Image', style: TextStyle(color: Colors.white)),
              onTap: () async {
                Navigator.pop(context);
                final picker = ImagePicker();
                final XFile? image = await picker.pickImage(source: ImageSource.gallery);
                if (image != null) _processFile(File(image.path));
              },
            ),
            ListTile(
              leading: const Icon(Icons.insert_drive_file, color: Colors.orange),
              title: const Text('File', style: TextStyle(color: Colors.white)),
              onTap: () async {
                Navigator.pop(context);
                final result = await FilePicker.platform.pickFiles();
                if (result != null && result.files.single.path != null) {
                  _processFile(File(result.files.single.path!));
                }
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _processFile(File file) async {
    try {
      final bytes = await file.readAsBytes();
      final base64 = base64Encode(bytes);
      final mime = lookupMimeType(file.path) ?? 'application/octet-stream';
      final name = file.path.split('/').last;

      setState(() {
        _selectedFile = file;
        _base64File = base64;
        _mimeType = mime;
        _fileName = name;
      });
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error processing file: $e')),
      );
    }
  }

  void _clearAttachment() {
    setState(() {
      _selectedFile = null;
      _base64File = null;
      _mimeType = null;
      _fileName = null;
    });
  }

  void _sendMessage() {
    final text = _controller.text.trim();
    if (text.isEmpty && _selectedFile == null) return;

    ref.read(chatNotifierProvider.notifier).sendMessage(
      text, 
      attachment: _base64File,
      mimeType: _mimeType,
      fileName: _fileName
    );
    
    _controller.clear();
    _clearAttachment();
    _focusNode.unfocus();

    Future.delayed(const Duration(milliseconds: 300), () {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final chatState = ref.watch(chatNotifierProvider);
    // Auto-scroll listener... (same as original)
    ref.listen<AsyncValue<List<Message>>>(chatNotifierProvider, (previous, next) {
      next.whenData((messages) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (_scrollController.hasClients) {
            _scrollController.animateTo(
              _scrollController.position.maxScrollExtent,
              duration: const Duration(milliseconds: 300),
              curve: Curves.easeOut,
            );
          }
        });
      });
    });

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        // ... (Header same as original)
        backgroundColor: Colors.black,
        elevation: 0,
        leading: Builder(
          builder: (context) => IconButton(
            icon: const Icon(Icons.menu, color: Colors.white),
            onPressed: () => Scaffold.of(context).openDrawer(),
          ),
        ),
        title: const Text('Stremini AI', style: TextStyle(color: Colors.white)),
        centerTitle: true,
      ),
      drawer: _buildDrawer(), // (Use your existing drawer method)
      body: Column(
        children: [
          Expanded(
            child: chatState.when(
              data: (messages) => ListView.builder(
                controller: _scrollController,
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
                itemCount: messages.length,
                itemBuilder: (context, index) => _buildMessageBubble(messages[index]),
              ),
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (error, _) => Center(child: Text('Error: $error', style: const TextStyle(color: Colors.red))),
            ),
          ),

          // PREVIEW AREA
          if (_selectedFile != null)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              color: Colors.grey[900],
              child: Row(
                children: [
                  Container(
                    width: 40,
                    height: 40,
                    decoration: BoxDecoration(
                      color: Colors.grey[800],
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: _mimeType?.startsWith('image/') == true
                        ? ClipRRect(
                            borderRadius: BorderRadius.circular(8),
                            child: Image.file(_selectedFile!, fit: BoxFit.cover),
                          )
                        : const Icon(Icons.insert_drive_file, color: Colors.white),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      _fileName ?? 'Attached File',
                      style: const TextStyle(color: Colors.white, fontSize: 13),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close, color: Colors.red, size: 20),
                    onPressed: _clearAttachment,
                  )
                ],
              ),
            ),

          // INPUT AREA
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: BoxDecoration(
              color: Colors.black,
              border: Border(top: BorderSide(color: Colors.grey[800]!, width: 1)),
            ),
            child: SafeArea(
              child: Row(
                children: [
                  // Attachment Button
                  Container(
                    width: 44,
                    height: 44,
                    decoration: BoxDecoration(color: Colors.grey[900], shape: BoxShape.circle),
                    child: IconButton(
                      icon: const Icon(Icons.add, color: Colors.white, size: 24),
                      onPressed: _pickAttachment, // Call picker
                    ),
                  ),
                  const SizedBox(width: 12),
                  // Text Input
                  Expanded(
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      decoration: BoxDecoration(
                        color: Colors.grey[900],
                        borderRadius: BorderRadius.circular(24),
                      ),
                      child: TextField(
                        controller: _controller,
                        focusNode: _focusNode,
                        style: const TextStyle(color: Colors.white, fontSize: 15),
                        decoration: const InputDecoration(
                          hintText: 'Ask anything...',
                          hintStyle: TextStyle(color: Colors.grey, fontSize: 15),
                          border: InputBorder.none,
                          contentPadding: EdgeInsets.symmetric(vertical: 12),
                        ),
                        maxLines: null,
                        textInputAction: TextInputAction.send,
                        onSubmitted: (_) => _sendMessage(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  // Send Button
                  Container(
                    width: 44,
                    height: 44,
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(colors: [Color(0xFF23A6E2), Color(0xFF0066FF)]),
                      shape: BoxShape.circle,
                    ),
                    child: IconButton(
                      icon: const Icon(Icons.send, color: Colors.white, size: 20),
                      onPressed: _sendMessage,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  // MODIFIED MESSAGE BUBBLE FOR COPYING
  Widget _buildMessageBubble(Message message) {
    if (message.type == MessageType.typing) {
      return _buildTypingIndicatorBubble(); // Helper method for typing
    }

    final isUser = message.type == MessageType.user;
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(
        mainAxisAlignment: isUser ? MainAxisAlignment.end : MainAxisAlignment.start,
        children: [
          Flexible(
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: BoxDecoration(
                color: isUser ? Colors.grey[800] : Colors.transparent,
                borderRadius: BorderRadius.circular(20),
              ),
              // Use SelectableText to enable copying
              child: SelectableText(
                message.text,
                style: const TextStyle(color: Colors.white, fontSize: 15, height: 1.4),
                cursorColor: const Color(0xFF23A6E2),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTypingIndicatorBubble() {
     return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: BoxDecoration(
              color: Colors.grey[900],
              borderRadius: BorderRadius.circular(20),
            ),
            child: const Text("Typing...", style: TextStyle(color: Colors.white54)),
          ),
        ],
      ),
    );
  }
  
  // Re-include your original _buildDrawer() method here...
  Widget _buildDrawer() {
    return Drawer(
      backgroundColor: const Color(0xFF1A1A1A),
      child: Container(), // Placeholder: Insert your original drawer code here
    );
  }
}
