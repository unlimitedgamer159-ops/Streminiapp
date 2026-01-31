import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class ApiService {
  static const String baseUrl = "https://ai-keyboard-backend.vishwajeetadkine705.workers.dev";
  
  Future<void> initSession() async {}
  Future<void> clearSession() async {}

  Future<String> sendMessage(String userMessage, {
    String? attachment, 
    String? mimeType, 
    String? fileName,
    List<Map<String, dynamic>>? history 
  }) async {
    try {
      final Map<String, dynamic> bodyMap = {
        "message": userMessage,
        "history": history ?? [],
      };

      if (attachment != null) {
        bodyMap["attachment"] = <String, dynamic>{
          "data": attachment,
          "mime": mimeType,
          "name": fileName
        };
      }

      final response = await http.post(
        Uri.parse("$baseUrl/chat/message"),
        headers: {
          "Content-Type": "application/json",
          "Accept": "application/json",
        },
        body: jsonEncode(bodyMap),
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        if (data is Map) {
          return data['response'] ?? data['reply'] ?? data['message'] ?? "Empty reply.";
        }
        return data.toString();
      } else {
        // Parse error message for debugging
        try {
          final errData = jsonDecode(response.body);
          return "❌ Error: ${errData['error'] ?? response.statusCode}";
        } catch (_) {
          return "❌ Error ${response.statusCode}: ${response.body}";
        }
      }
    } catch (e) {
      return "⚠️ Network Error: $e";
    }
  }

  Stream<String> streamMessage(String userMessage, {List<Map<String, dynamic>>? history}) async* {
    try {
      final request = http.Request('POST', Uri.parse("$baseUrl/chat/stream"));
      request.headers.addAll({
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
      });
      
      request.body = jsonEncode({
        "message": userMessage,
        "history": history ?? [],
      });

      final streamedResponse = await request.send();

      if (streamedResponse.statusCode == 200) {
        await for (var chunk in streamedResponse.stream.transform(utf8.decoder)) {
          final lines = chunk.split('\n');
          for (var line in lines) {
            if (line.startsWith('data: ')) {
              final jsonStr = line.substring(6);
              if (jsonStr.trim().isNotEmpty && jsonStr != '[DONE]') {
                try {
                  final data = jsonDecode(jsonStr);
                  if (data is Map && data.containsKey('token')) {
                    yield data['token'] as String;
                  }
                } catch (_) {}
              }
            }
          }
        }
      } else {
        yield "❌ Error: ${streamedResponse.statusCode}";
      }
    } catch (e) {
      yield "⚠️ Error: $e";
    }
  }

  // Keep existing methods
  Future<SecurityScanResult> scanContent(String content) async { return SecurityScanResult(isSafe: true, riskLevel: 'low', tags: [], analysis: ''); }
  Future<VoiceCommandResult> parseVoiceCommand(String command) async { return VoiceCommandResult(action: '', parameters: {}); }
  Future<String> translateScreen(String content, String targetLanguage) async { return ""; }
  Future<String> completeText(String incompleteText) async { return ""; }
  Future<String> rewriteInTone(String text, String tone) async { return ""; }
  Future<String> translateText(String text, String targetLanguage) async { return ""; }
  Future<Map<String, dynamic>> checkHealth() async { return {}; }
}

class SecurityScanResult {
  final bool isSafe; final String riskLevel; final List<String> tags; final String analysis;
  SecurityScanResult({required this.isSafe, required this.riskLevel, required this.tags, required this.analysis});
}
class VoiceCommandResult {
  final String action; final Map<String, dynamic> parameters;
  VoiceCommandResult({required this.action, required this.parameters});
}

final apiServiceProvider = Provider<ApiService>((ref) => ApiService());
