# Stremini AI

**Package:** `com.example.stremini_chatbot`
**Platform:** Android (Hybrid Flutter/Kotlin)
**Backend:** Cloudflare Workers

## Overview

Stremini AI is a system-wide Android utility designed to enhance digital safety and productivity. The application leverages Android Accessibility Services and System Alert Windows to provide real-time screen analysis, threat detection (phishing/scams), and a persistent AI assistant overlay. The architecture consists of a Flutter-based frontend for configuration and in-app UI, coupled with native Kotlin services for system-level interactions.

## Technical Architecture

The project implements a hybrid architecture where Flutter handles the application lifecycle and UI, while native Android components manage background services and system overlays. Communication between the Dart (Flutter) and Kotlin (Native) layers is managed via `MethodChannel` and `EventChannel`.

### 1. Flutter Layer (Dart)
* **State Management:** Implemented using `flutter_riverpod`.
* **Networking:** Handles HTTP communication with the AI backend (`api_service.dart`).
* **UI Components:**
    * `HomeScreen`: Dashboard for managing permissions and service states.
    * `ChatScreen`: Full-screen interface for AI interaction.
* **Service Integration:** Uses `OverlayService` and `KeyboardService` to invoke native methods.

### 2. Native Android Layer (Kotlin)
The core functionality relies on three primary Android services:

* **`ScreenReaderService` (AccessibilityService):**
    * Extracts `AccessibilityNodeInfo` from the active window to parse text and view coordinates.
    * Analyzes screen content for semantic threats (scams, phishing URLs) using the backend API.
    * Draws native overlays (`TYPE_ACCESSIBILITY_OVERLAY`) to highlight suspicious elements directly on the screen.

* **`ChatOverlayService` (ForegroundService):**
    * Manages a persistent floating bubble (`TYPE_APPLICATION_OVERLAY`).
    * Implements a custom radial menu for quick access to tools.
    * Handles the floating chat window lifecycle, including drag gestures and view updates.

* **`MainActivity` (FlutterActivity):**
    * Acts as the bridge for `MethodChannel` calls (`stremini.chat.overlay`, `stremini.keyboard`).
    * Manages permission requests for Overlay and Accessibility settings.

## Features

### Real-Time Screen Scanning
The application scans visible text on the screen upon user request. It identifies potential security risks such as malicious URLs or social engineering patterns.
* **Detection:** Coordinates and text bounds are extracted via the Accessibility API.
* **Visualization:** Color-coded tags (Red for threats, Green for safe) are rendered over specific UI elements.
* **Banner System:** A dismissible top banner summarizes the scan results.

### Floating Assistant
A persistent overlay bubble provides immediate access to AI tools without leaving the current application.
* **Radial Menu:** options to trigger the scanner, open the chatbot, or access settings.
* **Context Aware:** The chat interface can receive context from the screen reader to answer queries about on-screen content.

### AI Keyboard Integration
The application includes references to an Input Method Service (`StreminiIME`), allowing for AI-assisted typing, translation, and text completion directly within text fields.

## Installation and Setup

### Prerequisites
* Flutter SDK
* Android SDK (API Level 26+)
* Java Development Kit (JDK)

### Build Instructions
1.  Clone the repository.
2.  Install dependencies:
    ```bash
    flutter pub get
    ```
3.  Run the application on a physical Android device (Emulators may restrict Accessibility/Overlay permissions):
    ```bash
    flutter run
    ```

## Permissions

The application requires the following sensitive permissions to function:

1.  **`SYSTEM_ALERT_WINDOW` (Display over other apps):**
    * Required by `ChatOverlayService` to render the floating bubble and chat window.
    * Required by `ScreenReaderService` to draw risk tags over third-party apps.

2.  **`BIND_ACCESSIBILITY_SERVICE`:**
    * Required by `ScreenReaderService` to read screen content and coordinates for analysis.

3.  **`FOREGROUND_SERVICE`:**
    * Ensures the `ChatOverlayService` remains active in the background without being killed by the system.

4.  **`INTERNET`:**
    * Used to communicate with the AI backend endpoints.
