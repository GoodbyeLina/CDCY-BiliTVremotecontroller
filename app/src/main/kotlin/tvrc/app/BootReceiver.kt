package tvrc.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** 开机自启（R-03「电视盒子式常驻」）：有配置文件才拉起，避免第一次装完就弹通知。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val cfg = File(context.filesDir, "config.json")
        if (!cfg.isFile) return
        runCatching { PlaybackService.ensureRunning(context) }
    }
}
