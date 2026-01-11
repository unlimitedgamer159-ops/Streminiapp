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
import '../../utils/helpers.dart';
import '../chat/chat_screen.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(homeControllerProvider);
    final controller = ref.read(homeControllerProvider.notifier);
    
    // Show error if any
    ref.listen(homeControllerProvider, (previous, next) {
      if (next.errorMessage != null) {
        Helpers.showSnackBar(context, next.errorMessage!);
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
            
            _buildInfoCard(),
            const SizedBox(height: 32),
            
            if (!state.permissionStatus.hasAll) ...[
              Text('Required Permissions', style: AppTextStyles.h3),
              const SizedBox(height: 12),
              
              if (state.permissionStatus.needsOverlay)
                PermissionCard(
                  title: 'Overlay Permission',
                  description: 'Required for floating bubble over other apps',
                  icon: Icons.bubble_chart,
                  color: AppColors.warning,
                  onTap: () => controller.requestOverlayPermission(),
                ),
              
              if (state.permissionStatus.needsAccessibility)
                PermissionCard(
                  title: 'Accessibility Permission',
                  description: 'Required for screen scanner to read screen content and detect scams',
                  icon: Icons.accessibility_new,
                  color: AppColors.emotional,
                  onTap: () => controller.requestAccessibilityPermission(),
                ),
            ],
          ],
        ),
      ),
    );
  }

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
          Image.asset(AppAssets.logo, width: 32, height: 32, fit: BoxFit.contain),
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
              Text(Helpers.getGreeting(), style: AppTextStyles.h1),
              const Spacer(),
              AppContainer(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                color: AppColors.white.withOpacity(0.1),
                borderRadius: 20,
                border: BorderSide(color: AppColors.white.withOpacity(0.2)),
                onTap: () => Navigator.push(
                  context,
                  MaterialPageRoute(builder: (context) => const ChatScreen()),
                ),
                child: Row(
                  children: [
                    Icon(Icons.touch_app, color: AppColors.info.withOpacity(0.8), size: 16),
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
          Text(
            'Your AI-powered scam protection is ready',
            style: AppTextStyles.subtitle1,
          ),
        ],
      ),
    );
  }

  Widget _buildSmartChatbotCard(
    BuildContext context,
    HomeState state,
    HomeController controller,
  ) {
    return FeatureCard(
      title: 'Smart Chatbot & Scam Detector',
      description: 'Floating AI assistant with intelligent screen analyzer. Works system-wide across all apps!',
      icon: Icons.chat_bubble_outline,
      iconColor: AppColors.primary,
      status: state.bubbleActive ? 'Active' : 'Inactive',
      statusColor: state.bubbleActive ? AppColors.success : AppColors.lightGray,
      badges: const [
        'Floating Chat',
        'Screen Scanner',
        'Scam Detection',
        'System-Wide',
      ],
      trailing: Switch(
        value: state.bubbleActive,
        onChanged: state.isLoading ? null : (value) => controller.toggleBubble(value),
        activeColor: AppColors.primary,
      ),
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
              const Icon(Icons.info_outline, color: AppColors.scanCyan, size: 24),
              const SizedBox(width: 12),
              Text('How to use:', style: AppTextStyles.body2.copyWith(fontWeight: FontWeight.bold)),
            ],
          ),
          const SizedBox(height: 12),
          const InfoStep(number: '1', text: 'Tap the floating bubble to open radial menu'),
          const InfoStep(number: '2', text: 'Select Chat icon to start conversation'),
          const InfoStep(number: '3', text: 'Select Scanner icon to analyze screen for scams'),
          const InfoStep(number: '4', text: 'AI will show tags near suspicious content (Scam, Safe, Tone, etc.)'),
          const InfoStep(number: '5', text: 'Tap tags to see why content is flagged'),
          const InfoStep(number: '6', text: 'Tap scanner again to hide all tags'),
        ],
      ),
    );
  }
}
