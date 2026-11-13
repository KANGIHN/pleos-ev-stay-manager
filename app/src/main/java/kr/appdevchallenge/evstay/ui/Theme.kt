package kr.appdevchallenge.evstay.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val background = Color(0xFF0F1216)
    val surface = Color(0xFF1A1F26)
    val surfaceHigh = Color(0xFF242B34)
    val text = Color(0xFFECEFF3)
    val textDim = Color(0xFF9AA4B1)
    val ok = Color(0xFF4CC38A)
    val warn = Color(0xFFF2B33D)
    val danger = Color(0xFFEF5B5B)
    val info = Color(0xFF5AA9F2)
    val neutral = Color(0xFF7D8794)
    val battery = Color(0xFF4CC38A)
    val reserveHome = Color(0xFF5AA9F2)
    val reserveMargin = Color(0xFF8E7CF0)
}

@Composable
fun EvStayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.background,
            surface = Palette.surface,
            onBackground = Palette.text,
            onSurface = Palette.text,
            primary = Palette.ok
        ),
        content = content
    )
}
