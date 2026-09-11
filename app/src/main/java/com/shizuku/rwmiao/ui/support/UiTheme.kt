package com.shizuku.rwmiao.ui.support

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_BLUE
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_CYAN
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_DYNAMIC
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_GREEN
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_ORANGE
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_PINK
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_RED
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_YELLOW

@Composable
internal fun moduleColorScheme(
    colorMode: Int,
    darkTheme: Boolean,
    context: Context
): ColorScheme {
    return when (colorMode) {
        UI_COLOR_DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (darkTheme) darkColorScheme() else lightColorScheme()
        }
        UI_COLOR_GREEN -> if (darkTheme) greenDarkColorScheme() else greenLightColorScheme()
        UI_COLOR_BLUE -> if (darkTheme) blueDarkColorScheme() else blueLightColorScheme()
        UI_COLOR_PINK -> if (darkTheme) pinkDarkColorScheme() else pinkLightColorScheme()
        UI_COLOR_YELLOW -> if (darkTheme) yellowDarkColorScheme() else yellowLightColorScheme()
        UI_COLOR_ORANGE -> if (darkTheme) orangeDarkColorScheme() else orangeLightColorScheme()
        UI_COLOR_RED -> if (darkTheme) redDarkColorScheme() else redLightColorScheme()
        UI_COLOR_CYAN -> if (darkTheme) cyanDarkColorScheme() else cyanLightColorScheme()
        else -> if (darkTheme) darkColorScheme() else lightColorScheme()
    }
}

private fun ColorScheme.withCompleteLightM3Roles(): ColorScheme {
    val accent = primary
    val background = blendM3(Color(0xFFFFFBFE), accent, 0.035f)
    val surface = blendM3(Color(0xFFFFFBFE), accent, 0.035f)
    return copy(
        inversePrimary = primaryContainer,
        background = background,
        onBackground = Color(0xFF1A1B1F),
        surface = surface,
        onSurface = Color(0xFF1A1B1F),
        surfaceVariant = blendM3(Color(0xFFE7E0EC), accent, 0.10f),
        onSurfaceVariant = Color(0xFF46464F),
        surfaceTint = accent,
        inverseSurface = blendM3(Color(0xFF313033), accent, 0.10f),
        inverseOnSurface = Color(0xFFF4EFF4),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = blendM3(Color(0xFF74747D), accent, 0.08f),
        outlineVariant = blendM3(Color(0xFFC7C5CC), accent, 0.08f),
        scrim = Color.Black,
        surfaceBright = blendM3(Color(0xFFFFFBFE), accent, 0.02f),
        surfaceDim = blendM3(Color(0xFFDED8E1), accent, 0.08f),
        surfaceContainerLowest = blendM3(Color.White, accent, 0.015f),
        surfaceContainerLow = blendM3(Color(0xFFF7F2FA), accent, 0.04f),
        surfaceContainer = blendM3(Color(0xFFF3EDF7), accent, 0.06f),
        surfaceContainerHigh = blendM3(Color(0xFFECE6F0), accent, 0.07f),
        surfaceContainerHighest = blendM3(Color(0xFFE6E0E9), accent, 0.08f)
    )
}

private fun ColorScheme.withCompleteDarkM3Roles(): ColorScheme {
    val accent = primary
    val background = blendM3(Color(0xFF141218), accent, 0.08f)
    val surface = blendM3(Color(0xFF141218), accent, 0.08f)
    return copy(
        inversePrimary = primary,
        background = background,
        onBackground = Color(0xFFE6E1E9),
        surface = surface,
        onSurface = Color(0xFFE6E1E9),
        surfaceVariant = blendM3(Color(0xFF49454F), accent, 0.12f),
        onSurfaceVariant = Color(0xFFCAC4D0),
        surfaceTint = accent,
        inverseSurface = blendM3(Color(0xFFE6E1E9), accent, 0.03f),
        inverseOnSurface = Color(0xFF313033),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = blendM3(Color(0xFF938F99), accent, 0.10f),
        outlineVariant = blendM3(Color(0xFF49454F), accent, 0.12f),
        scrim = Color.Black,
        surfaceBright = blendM3(Color(0xFF3B383E), accent, 0.10f),
        surfaceDim = blendM3(Color(0xFF141218), accent, 0.08f),
        surfaceContainerLowest = blendM3(Color(0xFF0F0D13), accent, 0.06f),
        surfaceContainerLow = blendM3(Color(0xFF1D1B20), accent, 0.08f),
        surfaceContainer = blendM3(Color(0xFF211F26), accent, 0.10f),
        surfaceContainerHigh = blendM3(Color(0xFF2B2930), accent, 0.11f),
        surfaceContainerHighest = blendM3(Color(0xFF36333B), accent, 0.12f)
    )
}

private fun blendM3(base: Color, tint: Color, amount: Float): Color {
    val fraction = amount.coerceIn(0f, 1f)
    return Color(
        red = base.red + (tint.red - base.red) * fraction,
        green = base.green + (tint.green - base.green) * fraction,
        blue = base.blue + (tint.blue - base.blue) * fraction,
        alpha = base.alpha + (tint.alpha - base.alpha) * fraction
    )
}

private fun greenLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF006E1C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8BFA8D),
    onPrimaryContainer = Color(0xFF002204),
    secondary = Color(0xFF52634F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF101F0E),
    tertiary = Color(0xFF38656B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBCEBF1),
    onTertiaryContainer = Color(0xFF001F24)
).withCompleteLightM3Roles()

private fun greenDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF6DDF73),
    onPrimary = Color(0xFF00390A),
    primaryContainer = Color(0xFF005313),
    onPrimaryContainer = Color(0xFF8BFA8D),
    secondary = Color(0xFFB9CCB3),
    onSecondary = Color(0xFF243423),
    secondaryContainer = Color(0xFF3A4A37),
    onSecondaryContainer = Color(0xFFD5E8CF),
    tertiary = Color(0xFFA0CFD4),
    onTertiary = Color(0xFF00363B),
    tertiaryContainer = Color(0xFF1F4D52),
    onTertiaryContainer = Color(0xFFBCEBF1)
).withCompleteDarkM3Roles()

private fun blueLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF415F91),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF575E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBE2F9),
    onSecondaryContainer = Color(0xFF141B2C),
    tertiary = Color(0xFF705575),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBD8FD),
    onTertiaryContainer = Color(0xFF28132D)
).withCompleteLightM3Roles()

private fun blueDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF284777),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBFC6DC),
    onSecondary = Color(0xFF293041),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFDDBBE0),
    onTertiary = Color(0xFF3F2843),
    tertiaryContainer = Color(0xFF573E5B),
    onTertiaryContainer = Color(0xFFFBD8FD)
).withCompleteDarkM3Roles()

private fun pinkLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFFC2185B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E6),
    onPrimaryContainer = Color(0xFF3E001D),
    secondary = Color(0xFF99506D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E6),
    onSecondaryContainer = Color(0xFF2B151D),
    tertiary = Color(0xFF7E5733),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2E1600)
).withCompleteLightM3Roles()

private fun pinkDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB0D0),
    onPrimary = Color(0xFF650033),
    primaryContainer = Color(0xFF93004A),
    onPrimaryContainer = Color(0xFFFFD9E6),
    secondary = Color(0xFFE5B9CA),
    onSecondary = Color(0xFF422931),
    secondaryContainer = Color(0xFF603B4B),
    onSecondaryContainer = Color(0xFFFFD9E6),
    tertiary = Color(0xFFF0BE8D),
    onTertiary = Color(0xFF472A0F),
    tertiaryContainer = Color(0xFF5D3F25),
    onTertiaryContainer = Color(0xFFFFDCBE)
).withCompleteDarkM3Roles()

private fun yellowLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF6D5F00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF8E287),
    onPrimaryContainer = Color(0xFF211B00),
    secondary = Color(0xFF655F40),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE5C4),
    onSecondaryContainer = Color(0xFF1E1B0F),
    tertiary = Color(0xFF406651),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC2E8C8),
    onTertiaryContainer = Color(0xFF00210D)
).withCompleteLightM3Roles()

private fun yellowDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFFDBC66E),
    onPrimary = Color(0xFF3A3200),
    primaryContainer = Color(0xFF514700),
    onPrimaryContainer = Color(0xFFF8E287),
    secondary = Color(0xFFD0C7A0),
    onSecondary = Color(0xFF353119),
    secondaryContainer = Color(0xFF4C472D),
    onSecondaryContainer = Color(0xFFECE5C4),
    tertiary = Color(0xFFA6D0AC),
    onTertiary = Color(0xFF12371F),
    tertiaryContainer = Color(0xFF28512F),
    onTertiaryContainer = Color(0xFFC2E8C8)
).withCompleteDarkM3Roles()

private fun orangeLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFFA63C00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF3A0D00),
    secondary = Color(0xFF87532E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC7),
    onSecondaryContainer = Color(0xFF301400),
    tertiary = Color(0xFF765A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE08B),
    onTertiaryContainer = Color(0xFF241A00)
).withCompleteLightM3Roles()

private fun orangeDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB596),
    onPrimary = Color(0xFF5F1A00),
    primaryContainer = Color(0xFF7E2F00),
    onPrimaryContainer = Color(0xFFFFDBCA),
    secondary = Color(0xFFFFB78D),
    onSecondary = Color(0xFF4D260E),
    secondaryContainer = Color(0xFF683C20),
    onSecondaryContainer = Color(0xFFFFDBC7),
    tertiary = Color(0xFFE5C56A),
    onTertiary = Color(0xFF3D3000),
    tertiaryContainer = Color(0xFF594600),
    onTertiaryContainer = Color(0xFFFFE08B)
).withCompleteDarkM3Roles()

private fun redLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFFBA1A1A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775653),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1513),
    tertiary = Color(0xFF705C2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE0A6),
    onTertiaryContainer = Color(0xFF251A00)
).withCompleteLightM3Roles()

private fun redDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE7BDB8),
    onSecondary = Color(0xFF442927),
    secondaryContainer = Color(0xFF5D3F3C),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFDEC48C),
    onTertiary = Color(0xFF3B2F05),
    tertiaryContainer = Color(0xFF534619),
    onTertiaryContainer = Color(0xFFFBE0A6)
).withCompleteDarkM3Roles()

private fun cyanLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF97F0FF),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF4A6267),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE7EC),
    onSecondaryContainer = Color(0xFF051F23),
    tertiary = Color(0xFF525E7D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9E2FF),
    onTertiaryContainer = Color(0xFF0C1B36)
).withCompleteLightM3Roles()

private fun cyanDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF4FD8EB),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = Color(0xFFB1CBD0),
    onSecondary = Color(0xFF1C3337),
    secondaryContainer = Color(0xFF334B4F),
    onSecondaryContainer = Color(0xFFCDE7EC),
    tertiary = Color(0xFFBCC6EA),
    onTertiary = Color(0xFF222E4D),
    tertiaryContainer = Color(0xFF394665),
    onTertiaryContainer = Color(0xFFD9E2FF)
).withCompleteDarkM3Roles()
