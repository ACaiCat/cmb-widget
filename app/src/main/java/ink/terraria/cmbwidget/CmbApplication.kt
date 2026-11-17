package ink.terraria.cmbwidget

import android.app.Application
import ink.terraria.cmbwidget.sync.MailSync

class CmbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MailSync.schedule(this)
    }
}
