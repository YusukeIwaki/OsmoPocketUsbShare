package io.yusukeiwaki.osmopocketusbshare.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun OsmoPocketTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = when {
        darkTheme -> runCatching { dynamicDarkColorScheme(context) }.getOrElse { darkColorScheme() }
        else -> runCatching { dynamicLightColorScheme(context) }.getOrElse { lightColorScheme() }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
