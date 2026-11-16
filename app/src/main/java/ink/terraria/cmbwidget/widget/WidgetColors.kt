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
    private val scheme=appColorScheme(context)
    fun text(views: RemoteViews,id: Int,role: (ColorScheme) -> Color) {
        views.setTextColor(id,role(scheme).toArgb())
    }
    fun background(views: RemoteViews,id: Int,role: (ColorScheme) -> Color) {
        views.setColorStateList(id,"setBackgroundTintList",ColorStateList.valueOf(role(scheme).toArgb()))
    }
    fun icon(views: RemoteViews,id: Int,role: (ColorScheme) -> Color) {
        views.setColorStateList(id,"setImageTintList",ColorStateList.valueOf(role(scheme).toArgb()))
    }
}
