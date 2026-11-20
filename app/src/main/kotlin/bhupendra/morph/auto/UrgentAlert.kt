package bhupendra.morph.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import bhupendra.morph.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Emergency(
    val who: String,
    val app: String,
    val reason: String,
    val message: String,
    val at: Long = System.currentTimeMillis(),
    /** Opens the original conversation. */
    val open: PendingIntent? = null,
)

/**
 * The "a human needs you NOW" alarm: alarm-stream sound that pierces silent mode, a heartbeat
 * vibration and a heads-up notification. Stops on acknowledgement, or after [MAX_MS].
 */
object UrgentAlert {
    private const val CHANNEL = "urgent"
    private const val NOTIF_ID = 911
    private const val MAX_MS = 180_000L
    const val ACTION_ACK = "bhupendra.morph.ACK_URGENT"

    private val _active = MutableStateFlow<Emergency?>(null)
    val active: StateFlow<Emergency?> = _active

    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var restoreAlarmVolume: Int? = null

    fun raise(context: Context, e: Emergency) {
        main.post {
            _active.value = e
            startSound(context)
            vibrate(context)
            postNotification(context, e)
            main.removeCallbacksAndMessages(TIMEOUT)
            main.postDelayed({ silence(context) }, TIMEOUT, MAX_MS)
        }
    }

    /** User acknowledged: stop the noise, keep the card until they dismiss it. */
    fun acknowledge(context: Context) {
        silence(context)
        _active.value = null
    }

    private fun silence(context: Context) {
        main.post {
            ringtone?.stop(); ringtone = null
            val audio = context.getSystemService(AudioManager::class.java)
            restoreAlarmVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
            restoreAlarmVolume = null
            context.getSystemService(VibratorManager::class.java).cancel()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }
    }

    private fun startSound(context: Context) {
        if (ringtone?.isPlaying == true) return
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val cur = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val target = (max * 0.8f).toInt()
        if (cur < target) {
            restoreAlarmVolume = cur
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
    }

    private fun vibrate(context: Context) {
        val heartbeat = VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 900), 0)
        context.getSystemService(VibratorManager::class.java).vibrate(
            android.os.CombinedVibration.createParallel(heartbeat),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
    }

    private fun postNotification(context: Context, e: Emergency) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Urgent messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When Morph detects someone needs you right now"
            setBypassDnd(true)
            setSound(null, null)
        })
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openMorph = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val ack = PendingIntent.getBroadcast(context, 2, Intent(ACTION_ACK).setPackage(context.packageName), flags)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("⚠️ ${e.who} needs you — ${e.reason}")
            .setContentText(e.message)
            .setStyle(Notification.BigTextStyle().bigText("${e.message}\n\nMorph sent a holding reply on ${e.app}."))
            .setCategory(Notification.CATEGORY_ALARM)
            .setColor(0xFFD32F2F.toInt())
            .setOngoing(true)
            .setContentIntent(openMorph)
            .addAction(Notification.Action.Builder(null, "I'm on it", ack).build())
            .apply { e.open?.let { addAction(Notification.Action.Builder(null, "Open chat", it).build()) } }
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    private val TIMEOUT = Any()
}

/** Handles "I'm on it" from the notification. Not exported: only our own PendingIntent can reach it. */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UrgentAlert.ACTION_ACK) UrgentAlert.acknowledge(context.applicationContext)
    }
}
