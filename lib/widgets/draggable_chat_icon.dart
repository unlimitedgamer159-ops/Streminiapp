import 'package:flutter/material.dart';
import 'dart:math' as math;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/keyboard_service.dart';
import '../providers/scanner_provider.dart';

/// --------------------------------------------------------------
/// 🔵🟣 GLOW BUTTON (matches the screenshot UI)
/// --------------------------------------------------------------
class GlowCircleButton extends StatelessWidget {
  final IconData icon;
  final double size;
  final VoidCallback onTap;
  final bool isActive;

  const GlowCircleButton({
    super.key,
    required this.icon,
    required this.onTap,
    this.size = 70,
    this.isActive = false,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: Colors.white,
          boxShadow: isActive
              ? [
                  const BoxShadow(
                    color: Colors.white,
                    blurRadius: 3,
                    spreadRadius: 1,
                  ),
                ]
              : [
                  BoxShadow(
                    color: Colors.grey.withOpacity(0.3),
                    blurRadius: 2,
                    spreadRadius: 0,
                  ),
                ],
        ),
        child: Padding(
          padding: const EdgeInsets.all(5),
          child: Container(
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Colors.black,
              border: isActive
                  ? Border.all(
                      color: Colors.white.withOpacity(0.5),
                      width: 1,
                    )
                  : null,
            ),
            child: Icon(
              icon,
              color: Colors.white,
              size: size * 0.45,
            ),
          ),
        ),
      ),
    );
  }
}

/// --------------------------------------------------------------
/// 🔵 MAIN DRAGGABLE OVERLAY + RADIAL MENU
/// --------------------------------------------------------------
class DraggableChatIcon extends ConsumerStatefulWidget {
  final Offset position;
  final Function(Offset) onDragEnd;
  final String overlayMode;
  final VoidCallback onTapMain;
  final VoidCallback onOpenApp;
  final bool isScannerActive;

  const DraggableChatIcon({
    super.key,
    required this.position,
    required this.onDragEnd,
    required this.overlayMode,
    required this.onTapMain,
    required this.onOpenApp,
    this.isScannerActive = false,
  });

  @override
  ConsumerState<DraggableChatIcon> createState() => _DraggableChatIconState();
}

class _DraggableChatIconState extends ConsumerState<DraggableChatIcon>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _expandAnimation;
  late Animation<double> _rotateAnimation;
  late Offset _currentPosition;
  // Store the initial side (true for right, false for left)
  late bool _isRightSide;

  static const double _iconSize = 60.0;

  @override
  void initState() {
    super.initState();
    _currentPosition = widget.position;

    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
    );

    _expandAnimation =
        CurvedAnimation(parent: _controller, curve: Curves.easeOutBack);

    _rotateAnimation = Tween<double>(begin: 0.0, end: 0.125).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeInOut),
    );

    _updateAnimation(widget.overlayMode == "radial");
    // Determine initial side after first frame
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final screenWidth = MediaQuery.of(context).size.width;
      _isRightSide =
          (_currentPosition.dx + (_iconSize / 2)) > (screenWidth / 2);
    });
  }

  @override
  void didUpdateWidget(DraggableChatIcon oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.position != widget.position) {
      _currentPosition = widget.position;
    }
    if (oldWidget.overlayMode != widget.overlayMode) {
      _updateAnimation(widget.overlayMode == "radial");
    }
  }

  void _updateAnimation(bool isRadial) {
    if (isRadial) {
      _controller.forward();
    } else {
      _controller.reverse();
    }
  }

  /// --------------------------------------------------------------
  /// 🔵 RADIAL MENU WITH GLOW BUTTONS
  /// --------------------------------------------------------------
  Widget _buildRadialIcons(BuildContext context) {
    const double radius = 75.0;

    final screenWidth = MediaQuery.of(context).size.width;
    final bool isOnRightSide =
        (_currentPosition.dx + (_iconSize / 2)) > (screenWidth / 2);

    // Icons in your radial menu
    final keyboardService = KeyboardService();
    
    // UPDATED: Connected the Shield icon to the Scanner logic
    final List<Map<String, dynamic>> icons = [
      {'icon': Icons.message, 'action': () {}},
      {'icon': Icons.settings, 'action': () {}},
      {'icon': Icons.memory, 'action': () {}},
      {
        'icon': Icons.keyboard,
        'action': () {
          // Open system input method / keyboard settings (Android)
          keyboardService.openKeyboardSettingsActivity();
        }
      },
      {
        'icon': Icons.shield, 
        'action': () {
           // Toggle Scanning Mode
           ref.read(scannerStateProvider.notifier).toggleScanning();
        }
      },
    ];
    double startAngle;
    double endAngle;
    
    if (isOnRightSide) {
      // Icon is on the Right edge. Menu expands to the Left.
      startAngle = 90.0;
      endAngle = 270.0;
    } else {
      // Icon is on the Left edge. Menu expands to the Right.
      startAngle = 90.0;
      endAngle = -90.0;
    }
    final double step = (endAngle - startAngle) / (icons.length - 1);

    return Stack(
      alignment: Alignment.center,
      children: List.generate(icons.length, (index) {
        final double angle = startAngle + (index * step);
        final double rad = angle * (math.pi / 180.0);

        final double x = radius * math.cos(rad);
        final double y = radius * math.sin(rad);

        return AnimatedBuilder(
          animation: _expandAnimation,
          builder: (_, __) {
            return Transform.translate(
              offset: Offset(
                x * _expandAnimation.value,
                -y * _expandAnimation.value,
              ),
              child: Opacity(
                opacity: _expandAnimation.value.clamp(0.0, 1.0),
                child: GlowCircleButton(
                  icon: icons[index]['icon'],
                  size: 55,
                  onTap: () => icons[index]['action'](),
                  isActive: false,
                ),
              ),
            );
          },
        );
      }),
    );
  }

  /// --------------------------------------------------------------
  /// MAIN UI (drag icon + radial menu)
  /// --------------------------------------------------------------
  @override
  Widget build(BuildContext context) {
    final bool isRadial = widget.overlayMode == "radial";

    return Positioned(
      left: _currentPosition.dx,
      top: _currentPosition.dy,
      child: AbsorbPointer(
        absorbing: true,
        child: Material(
          color: Colors.transparent,
          child: Stack(
            clipBehavior: Clip.none,
            alignment: Alignment.center,
            children: [
              if (isRadial)
                IgnorePointer(
                  child: SizedBox(
                    width: _iconSize,
                    height: _iconSize,
                    child: _buildRadialIcons(context),
                  ),
                ),

              /// 🔥 MAIN GLOWING BUTTON
              GestureDetector(
                onPanUpdate: (details) {
                  // Update position without toggling radial menu during drag
                  setState(() {
                    _currentPosition += details.delta;
                  });
                },
                onPanEnd: (details) {
                  final screenWidth = MediaQuery.of(context).size.width;
                  final screenHeight = MediaQuery.of(context).size.height;
                  final topPadding = MediaQuery.of(context).padding.top;
                  final clampedX =
                      _isRightSide ? (screenWidth - _iconSize) : 0.0;
                  final clampedY = _currentPosition.dy
                      .clamp(topPadding, screenHeight - _iconSize);
                  _currentPosition = Offset(clampedX, clampedY);
                  widget.onDragEnd(_currentPosition);
                },
                onTap: widget.onTapMain,
                child: RotationTransition(
                  turns: isRadial
                      ? _rotateAnimation
                      : const AlwaysStoppedAnimation(0.0),
                  child: GlowCircleButton(
                    icon: Icons.local_fire_department_rounded,
                    size: 70,
                    onTap: widget.onTapMain,
                    isActive: widget.isScannerActive,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
