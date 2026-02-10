import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants/app_constants.dart';
import '../../core/constants/app_assets.dart';
import '../../core/theme/app_colors.dart';
import '../../core/theme/app_text_styles.dart';
import '../../core/widgets/app_container.dart';
import '../../core/widgets/app_drawer.dart';
import '../../core/widgets/feature_card.dart';
import '../../core/widgets/permission_card.dart';
import '../../core/widgets/info_step.dart';
import '../../controllers/home_controller.dart';
import '../../services/keyboard_service.dart';
import '../chat_screen.dart';

// ... (Keep existing keyboard providers) ...
final keyboardServiceProvider =
    Provider<KeyboardService>((ref) => KeyboardService());
final keyboardStatusProvider = FutureProvider<KeyboardStatus>((ref) async {
  final service = ref.watch(keyboardServiceProvider);
  return await service.checkKeyboardStatus();
});

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(homeControllerProvider.notifier).checkPermissions();
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(homeControllerProvider);
    final controller = ref.read(homeControllerProvider.notifier);
    final keyboardStatus = ref.watch(keyboardStatusProvider);

    ref.listen(homeControllerProvider, (previous, next) {
      if (next.errorMessage != null) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(next.errorMessage!),
            backgroundColor: AppColors.danger,
          ),
        );
        controller.clearError();
      }
    });

    return Scaffold(
      backgroundColor: AppColors.black,
      drawer: _buildDrawer(context),
      appBar: _buildAppBar(context),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildGreetingCard(context),
            const SizedBox(height: 32),

            Text('AI Features', style: AppTextStyles.h2),
            const SizedBox(height: 16),

            _buildSmartChatbotCard(context, state, controller),
            const SizedBox(height: 16),

            keyboardStatus.when(
              data: (status) => _buildKeyboardCard(context, status),
              loading: () => const SizedBox.shrink(),
              error: (_, __) => const SizedBox.shrink(),
            ),
            const SizedBox(height: 16),

            _buildInfoCard(),
            const SizedBox(height: 32),

            // Updated Permissions Section: Removed Accessibility/Scanner check
            if (state.permissionStatus.needsOverlay) ...[
              Text('Required Permissions', style: AppTextStyles.h3),
              const SizedBox(height: 12),
              PermissionCard(
                title: 'Overlay Permission',
                description: 'Required for floating bubble over other apps',
                icon: Icons.bubble_chart,
                color: AppColors.warning,
                onTap: () => controller.requestOverlayPermission(),
              ),
              const SizedBox(height: 16),
            ],
          ],
        ),
      ),
    );
  }

  // ... (Keep _buildAppBar and _buildDrawer as they were) ...
  PreferredSizeWidget _buildAppBar(BuildContext context) {
    return AppBar(
      backgroundColor: AppColors.black,
      elevation: 0,
      leading: Builder(
        builder: (context) => IconButton(
          icon: const Icon(Icons.menu, color: AppColors.white),
          onPressed: () => Scaffold.of(context).openDrawer(),
        ),
      ),
      title: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        mainAxisSize: MainAxisSize.min,
        children: [
          Image.asset(AppAssets.logo,
              width: 32, height: 32, fit: BoxFit.contain),
          const SizedBox(width: 12),
          Text(AppConstants.appName, style: AppTextStyles.h2),
        ],
      ),
      centerTitle: true,
      actions: const [SizedBox(width: 48)],
    );
  }

  Widget _buildDrawer(BuildContext context) {
    return AppDrawer(
      items: [
        AppDrawerItem(
          icon: Icons.home,
          title: 'Home',
          onTap: () => Navigator.pop(context),
        ),
        AppDrawerItem(
          icon: Icons.keyboard,
          title: 'AI Keyboard',
          onTap: () {
            Navigator.pop(context);
            ref.read(keyboardServiceProvider).openKeyboardSettingsActivity();
          },
        ),
        AppDrawerItem(
          icon: Icons.settings,
          title: 'Settings',
          onTap: () => Navigator.pop(context),
        ),
        AppDrawerItem(
          icon: Icons.help_outline,
          title: 'Contact Us',
          onTap: () => Navigator.pop(context),
        ),
      ],
    );
  }

  Widget _buildGreetingCard(BuildContext context) {
    return AppContainer(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [
          AppColors.primary.withOpacity(0.2),
          AppColors.secondary.withOpacity(0.2),
        ],
      ),
      border: BorderSide(color: AppColors.info.withOpacity(0.3)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(_getGreeting(), style: AppTextStyles.h1),
              const Spacer(),
              AppContainer(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                color: AppColors.white.withOpacity(0.1),
                borderRadius: 20,
                border: BorderSide(color: AppColors.white.withOpacity(0.2)),
                onTap: () => Navigator.push(
                  context,
                  MaterialPageRoute(builder: (context) => const ChatScreen()),
                ),
                child: Row(
                  children: [
                    Icon(Icons.touch_app,
                        color: AppColors.info.withOpacity(0.8), size: 16),
                    const SizedBox(width: 4),
                    Text(
                      'Quick Chat',
                      style: AppTextStyles.body3.copyWith(
                        color: AppColors.info.withOpacity(0.8),
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          // Updated Text: Removed "scam protection"
          Text(
            'Your AI-powered assistant is ready',
            style: AppTextStyles.subtitle1,
          ),
        ],
      ),
    );
  }

  String _getGreeting() {
    final hour = DateTime.now().hour;
    if (hour < 12) {
      return 'Good morning! 👋';
    } else if (hour < 18) {
      return 'Good afternoon! 👋';
    } else {
      return 'Good evening! 👋';
    }
  }

  Widget _buildSmartChatbotCard(
    BuildContext context,
    HomeState state,
    HomeController controller,
  ) {
    return FeatureCard(
      title: 'Smart Chatbot', // Removed "& Scam Detector"
      description: 'Floating AI assistant', // Removed "with real-time screen analyzer"
      icon: Icons.chat_bubble_outline,
      iconColor: AppColors.primary,
      status: state.bubbleActive ? 'Active' : 'Inactive',
      statusColor: state.bubbleActive ? AppColors.success : AppColors.lightGray,
      badges: const [
        'Floating Chat',
        // Removed: 'Screen Scanner', 'Scam Detection',
      ],
      trailing: state.isLoading
          ? const SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                valueColor: AlwaysStoppedAnimation(AppColors.primary),
              ),
            )
          : Switch(
              value: state.bubbleActive,
              onChanged: state.isLoading
                  ? null
                  : (value) async {
                      final success = await controller.toggleBubble(value);
                      if (!success && mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(
                            content:
                                const Text('Please grant permissions first'),
                            backgroundColor: AppColors.warning,
                          ),
                        );
                      } else if (success && mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(
                            content: Text(
                              value
                                  ? 'Floating bubble activated!'
                                  : 'Floating bubble deactivated',
                            ),
                            backgroundColor:
                                value ? AppColors.success : AppColors.lightGray,
                          ),
                        );
                      }
                    },
              activeColor: AppColors.primary,
            ),
    );
  }

  Widget _buildKeyboardCard(BuildContext context, KeyboardStatus status) {
    final keyboardService = ref.read(keyboardServiceProvider);

    return FeatureCard(
      title: 'AI-Powered Keyboard',
      description: 'Smart typing with translation, completion & enhancement',
      icon: Icons.keyboard,
      iconColor: AppColors.secondary,
      status: status.isActive
          ? 'Active'
          : status.isEnabled
              ? 'Enabled'
              : 'Disabled',
      statusColor: status.isActive
          ? AppColors.success
          : status.isEnabled
              ? AppColors.warning
              : AppColors.lightGray,
      badges: const [
        'Translate',
        'Complete',
        'Enhance',
        'Emoji',
      ],
      trailing: IconButton(
        icon: const Icon(Icons.settings, color: AppColors.secondary),
        onPressed: () => keyboardService.openKeyboardSettingsActivity(),
      ),
      onTap: () {
        if (!status.isEnabled) {
          keyboardService.openKeyboardSettings();
        } else if (!status.isSelected) {
          keyboardService.showKeyboardPicker();
        } else {
          keyboardService.openKeyboardSettingsActivity();
        }
      },
    );
  }

  Widget _buildInfoCard() {
    return AppContainer(
      padding: const EdgeInsets.all(16),
      border: BorderSide(color: AppColors.scanCyan.withOpacity(0.3)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.info_outline,
                  color: AppColors.scanCyan, size: 24),
              const SizedBox(width: 12),
              Text('How to use:',
                  style: AppTextStyles.body2
                      .copyWith(fontWeight: FontWeight.bold)),
            ],
          ),
          const SizedBox(height: 12),
          const InfoStep(number: '1', text: 'Grant required permissions'),
          const InfoStep(number: '2', text: 'Toggle Smart Chatbot ON'),
          const InfoStep(
              number: '3', text: 'Tap the floating bubble to open menu'),
          // Removed InfoStep 4 about Scanner
          const InfoStep(
              number: '4', // Renumbered from 5
              text: 'Enable AI Keyboard for smart typing',
              color: AppColors.secondary),
        ],
      ),
    );
  }
}
