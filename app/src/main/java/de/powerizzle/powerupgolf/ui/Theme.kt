package de.powerizzle.powerupgolf.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Grass = Color(0xFF5DBB63)
val Rough = Color(0xFF2E6B34)
val DeepGreen = Color(0xFF1B4D23)
val Gold = Color(0xFFFFC94A)

@Composable
fun PowerUpGolfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2E7D32),
            secondary = Color(0xFFFFA000),
            tertiary = Color(0xFFE53935),
            background = Color(0xFFF1F8E9),
            surface = Color(0xFFFFFFFF),
        ),
        content = content,
    )
}
