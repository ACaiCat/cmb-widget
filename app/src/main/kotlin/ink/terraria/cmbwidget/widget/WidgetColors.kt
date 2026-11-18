package ink.terraria.cmbwidget.widget

import android.content.Context
import android.content.res.ColorStateList
import android.widget.RemoteViews
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ink.terraria.cmbwidget.ui.theme.appColorScheme

/** Uses the same MD3 scheme as Compose; XML supplies opaque, theme-aware host fallbacks. */
class WidgetColors(context: Context) {
    private val light = appColorScheme(context, darkTheme = false)
    private val dark = appColorScheme(context, darkTheme = true)
    fun text(views: RemoteViews,id: Int,role: (ColorScheme) -> Color) {
        views.setColorInt(id, "setTextColor", role(light).toArgb(), role(dark).toArgb())
    }
    fun background(views: RemoteViews,id: Int,role: (ColorScheme) -> Color) {
        tint(views, id, "setBackgroundTintList", role)
    }
    fun rowBackground(views: RemoteViews, id: Int) {
        tint(views, id, "setBackgroundTintList", { it.surfaceContainer }, { it.surfaceContainerHighest })
    }
    fun icon(views: RemoteViews,id: Int,role: (ColorScheme) -> Color) {
        tint(views, id, "setImageTintList", role)
    }

    private fun tint(views: RemoteViews, id: Int, method: String, role: (ColorScheme) -> Color, nightRole: (ColorScheme) -> Color = role) {
        views.setColorStateList(id, method, ColorStateList.valueOf(role(light).toArgb()), ColorStateList.valueOf(nightRole(dark).toArgb()))
    }
}
