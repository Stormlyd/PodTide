package eu.darken.capod.main.ui.settings.general

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import eu.darken.capod.common.WebpageTool

internal object SettingsHelpLauncher {
    fun openFindDevices(context: Context): Boolean =
        WebpageTool(context).open("https://www.icloud.com/find")

    fun openAppDetails(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}
