package bhupendra.morph.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import bhupendra.morph.MainActivity
import bhupendra.morph.R

/**
 * Tells you about conversations that kept working after you left them: when one finishes, or when it needs your
 * answer. Tapping opens Morph straight into that conversation (MainActivity reads [EXTRA_RESUME]).
 */
class TaskNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a conversation finishes in the background, or needs your answer"
        })
    }

    fun finished(conversationId: String, title: String, summary: String) =
        post(conversationId, "Done: $title", summary)

    fun needsYou(conversationId: String, title: String) =
        post(conversationId, "$title needs your answer", "Tap to pick up where it's waiting.")

    fun clear(conversationId: String) = nm.cancel(TAG, conversationId.hashCode())

    private fun post(conversationId: String, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, conversationId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_RESUME, conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            nm.notify(TAG, conversationId.hashCode(), Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    companion object {
        const val CHANNEL = "morph_tasks"
        const val TAG = "conversation"
        const val EXTRA_RESUME = "morph.resume"
    }
}
