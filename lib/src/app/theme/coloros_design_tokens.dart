import 'package:material_ui/material_ui.dart';

/// Shared spacing, shape and neutral surfaces for the ColorOS-inspired UI.
abstract final class ColorOsDesignTokens {
  static const double pagePadding = 20;
  static const double pageMaxWidth = 1040;
  static const double sectionSpacing = 28;
  static const double cardSpacing = 12;
  static const double cardRadius = 28;
  static const double iconRadius = 20;
  static const double minimumTouchTarget = 48;
  static ColorScheme colorScheme(Brightness brightness, {ColorScheme? base}) {
    final dark = brightness == Brightness.dark;
    final scheme =
        base ??
        ColorScheme.fromSeed(
          seedColor: const Color(0xFF3157D5),
          brightness: brightness,
        );
    return scheme.copyWith(
      brightness: brightness,
      primary: dark ? const Color(0xFFADBEFF) : const Color(0xFF3157D5),
      onPrimary: dark ? const Color(0xFF10275D) : Colors.white,
      primaryContainer: dark
          ? const Color(0xFF25386A)
          : const Color(0xFFE8EEFF),
      onPrimaryContainer: dark
          ? const Color(0xFFE1E8FF)
          : const Color(0xFF173890),
      secondary: dark ? const Color(0xFFCAB8FF) : const Color(0xFF6D4ACB),
      onSecondary: dark ? const Color(0xFF38216B) : Colors.white,
      secondaryContainer: dark
          ? const Color(0xFF3B2C60)
          : const Color(0xFFF0EAFF),
      onSecondaryContainer: dark
          ? const Color(0xFFEEE5FF)
          : const Color(0xFF43227F),
      surface: dark ? const Color(0xFF1D2028) : Colors.white,
      onSurface: dark ? const Color(0xFFF2F3F7) : const Color(0xFF171A23),
      onSurfaceVariant: dark
          ? const Color(0xFFB8BFCE)
          : const Color(0xFF5B6070),
      surfaceContainerLowest: dark
          ? const Color(0xFF111318)
          : const Color(0xFFF3F4F8),
      surfaceContainerLow: dark
          ? const Color(0xFF171A20)
          : const Color(0xFFF8F9FC),
      surfaceContainer: dark
          ? const Color(0xFF242832)
          : const Color(0xFFEAEDF4),
      surfaceContainerHigh: dark ? const Color(0xFF20232B) : Colors.white,
      surfaceContainerHighest: dark
          ? const Color(0xFF303541)
          : const Color(0xFFE5E8F0),
      outline: dark ? const Color(0xFF7B8497) : const Color(0xFF747D90),
      outlineVariant: dark ? const Color(0xFF353B49) : const Color(0xFFE0E4ED),
      surfaceTint: Colors.transparent,
    );
  }

  static const textTheme = TextTheme(
    headlineLarge: TextStyle(
      fontSize: 36,
      height: 1.2,
      fontWeight: FontWeight.w700,
      letterSpacing: -0.8,
    ),
    headlineMedium: TextStyle(
      fontSize: 30,
      height: 1.25,
      fontWeight: FontWeight.w700,
      letterSpacing: -0.5,
    ),
    headlineSmall: TextStyle(
      fontSize: 25,
      height: 1.3,
      fontWeight: FontWeight.w600,
      letterSpacing: -0.3,
    ),
    titleLarge: TextStyle(
      fontSize: 22,
      height: 1.35,
      fontWeight: FontWeight.w600,
      letterSpacing: -0.2,
    ),
    titleMedium: TextStyle(
      fontSize: 17,
      height: 1.4,
      fontWeight: FontWeight.w600,
    ),
    titleSmall: TextStyle(
      fontSize: 15,
      height: 1.4,
      fontWeight: FontWeight.w600,
    ),
    bodyLarge: TextStyle(fontSize: 16, height: 1.5),
    bodyMedium: TextStyle(fontSize: 14, height: 1.5),
    bodySmall: TextStyle(fontSize: 12, height: 1.45),
    labelLarge: TextStyle(
      fontSize: 14,
      height: 1.4,
      fontWeight: FontWeight.w600,
    ),
    labelMedium: TextStyle(
      fontSize: 12,
      height: 1.4,
      fontWeight: FontWeight.w600,
    ),
  );
}
