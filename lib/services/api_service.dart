import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

class ApiService {
  static const String baseUrl = "https://ai-keyboard-backend.vishwajeetadkine705.workers.dev";

  Future<String> sendMessage(String userMessage, {String? attachment, String? mimeType, String? fileName}) async {
    try {
      final bodyMap = {
        "message": userMessage,
      };

      if (attachment != null) {
        bodyMap["attachment"] = {
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
        if (data is Map && data.containsKey('response')) {
          return data['response'] ?? "Empty reply.";
        }
        return data.toString();
      } else {
        return "❌ Server error: ${response.statusCode} - ${response.body}";
      }
    } catch (e) {
      return "⚠️ Network error: $e";
    }
  }
}

final apiServiceProvider = Provider<ApiService>((ref) => ApiService());
