import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

class AppContainer extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry? padding;
  final EdgeInsetsGeometry? margin;
  final Color? color;
  final double? borderRadius;
  final BorderSide? border;
  final List<BoxShadow>? shadows;
  final Gradient? gradient;
  final double? width;
  final double? height;
  final VoidCallback? onTap;
  
  const AppContainer({
    super.key,
    required this.child,
    this.padding,
    this.margin,
    this.color,
    this.borderRadius,
    this.border,
    this.shadows,
    this.gradient,
    this.width,
    this.height,
    this.onTap,
  });
  
  @override
  Widget build(BuildContext context) {
    final container = Container(
      width: width,
      height: height,
      padding: padding,
      margin: margin,
      decoration: BoxDecoration(
        color: gradient == null ? (color ?? AppColors.darkGray) : null,
        gradient: gradient,
        borderRadius: BorderRadius.circular(borderRadius ?? 16),
        border: border != null ? Border.fromBorderSide(border!) : null,
        boxShadow: shadows,
      ),
      child: child,
    );
    
    if (onTap != null) {
      return InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(borderRadius ?? 16),
        child: container,
      );
    }
    
    return container;
  }
}
