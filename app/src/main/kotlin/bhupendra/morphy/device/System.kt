package bhupendra.morphy.device

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object SystemUi {
    /**
     * Pulls down the notification shade (swipe down on home, like every launcher).
     * StatusBarManager#expandNotificationsPanel isn't public SDK; launchers call it reflectively
     * with the normal EXPAND_STATUS_BAR permission. Returns false if the platform blocks it.
     */
    @SuppressLint("WrongConstant")
    fun expandNotifications(context: Context): Boolean = runCatching {
        val sb = context.getSystemService("statusbar") ?: return false
        Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        true
    }.getOrDefault(false)
}

object Network {
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
