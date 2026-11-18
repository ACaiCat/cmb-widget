package ink.terraria.cmbwidget

import android.app.Application
import android.content.res.Configuration
import ink.terraria.cmbwidget.sync.MailSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CmbApplication : Application() {
    private val widgetScope = CoroutineScope(Dispatchers.IO)
    private var nightMode = Configuration.UI_MODE_NIGHT_UNDEFINED

    override fun onCreate() {
        super.onCreate()
        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        MailSync.schedule(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val mode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (mode == nightMode) return
        nightMode = mode
        widgetScope.launch { MailSync.notify(this@CmbApplication) }
    }
}
