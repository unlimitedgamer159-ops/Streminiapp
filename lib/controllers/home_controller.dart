import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/permission_service.dart';
import '../services/overlay_service.dart';

class HomeState {
  final bool isLoading;
  final bool bubbleActive;
  final PermissionStatus permissionStatus;
  final String? errorMessage;
  
  const HomeState({
    this.isLoading = false,
    this.bubbleActive = false,
    this.permissionStatus = const PermissionStatus(hasOverlay: false, hasAccessibility: false),
    this.errorMessage,
  });
  
  HomeState copyWith({bool? isLoading, bool? bubbleActive, PermissionStatus? permissionStatus, String? errorMessage}) {
    return HomeState(
      isLoading: isLoading ?? this.isLoading,
      bubbleActive: bubbleActive ?? this.bubbleActive,
      permissionStatus: permissionStatus ?? this.permissionStatus,
      errorMessage: errorMessage,
    );
  }
}

class HomeController extends Notifier<HomeState> {
  PermissionService get _permissionService => ref.read(permissionServiceProvider);
  OverlayService get _overlayService => ref.read(overlayServiceProvider);
  
  @override
  HomeState build() {
    Future.microtask(() => checkPermissions());
    return const HomeState();
  }
  
  Future<void> checkPermissions() async {
    state = state.copyWith(isLoading: true);
    try {
      final status = await _permissionService.checkAllPermissions();
      state = state.copyWith(permissionStatus: status, isLoading: false);
    } catch (e) {
      state = state.copyWith(isLoading: false, errorMessage: 'Failed to check permissions');
    }
  }
  
  Future<void> requestOverlayPermission() async {
    try {
      await _permissionService.requestOverlayPermission();
      await Future.delayed(const Duration(seconds: 1));
      await checkPermissions();
    } catch (e) {
      state = state.copyWith(errorMessage: 'Failed to request overlay permission');
    }
  }
  
  Future<void> requestAccessibilityPermission() async {
    try {
      await _permissionService.requestAccessibilityPermission();
      await Future.delayed(const Duration(seconds: 1));
      await checkPermissions();
    } catch (e) {
      state = state.copyWith(errorMessage: 'Failed to request accessibility permission');
    }
  }
  
  Future<bool> toggleBubble(bool value) async {
    if (!state.permissionStatus.hasOverlay) {
      await requestOverlayPermission();
      return false;
    }
    if (!state.permissionStatus.hasAccessibility && value) {
      await requestAccessibilityPermission();
      return false;
    }
    try {
      if (value) {
        await _overlayService.startOverlay();
      } else {
        await _overlayService.stopOverlay();
      }
      state = state.copyWith(bubbleActive: value);
      return true;
    } catch (e) {
      state = state.copyWith(errorMessage: e.toString());
      return false;
    }
  }
  
  void clearError() {
    state = state.copyWith(errorMessage: null);
  }
}

final permissionServiceProvider = Provider<PermissionService>((ref) => PermissionService());
final overlayServiceProvider = Provider<OverlayService>((ref) => OverlayService());
final homeControllerProvider = NotifierProvider<HomeController, HomeState>(HomeController.new);
