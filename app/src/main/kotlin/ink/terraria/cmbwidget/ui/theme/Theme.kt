package ink.terraria.cmbwidget.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

fun appColorScheme(context: Context, darkTheme: Boolean =
    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
    dynamicColor: Boolean = true
): ColorScheme = when {
    dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
    dynamicColor -> dynamicLightColorScheme(context)
    darkTheme -> darkColorScheme()
    else -> lightColorScheme()
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun CMBWidgetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialExpressiveTheme(
        colorScheme = appColorScheme(LocalContext.current, darkTheme, dynamicColor),
        typography = Typography,
        content = content
    )
}
