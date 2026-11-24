package bhupendra.morphy.device

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One app's media session, as the home screen needs it. */
data class Player(
    val id: String,
    val pkg: String,
    val app: String,
    val title: String,
    val artist: String?,
    val art: Bitmap?,
    val playing: Boolean,
    /** Position at [updatedAt] (elapsedRealtime); extrapolate with [speed] while playing. */
    val position: Long,
    val updatedAt: Long,
    val speed: Float,
    val duration: Long,
    val canPrev: Boolean,
    val canNext: Boolean,
    internal val controller: MediaController,
) {
    fun positionNow(): Long =
        if (!playing || duration <= 0) position
        else (position + ((SystemClock.elapsedRealtime() - updatedAt) * speed).toLong()).coerceIn(0, duration)
}

/**
 * Live media sessions (Apple Music, YouTube, Skipii…) straight from the system — real playing/paused state and
 * transport controls, instead of guessing from notifications. Needs notification access (that's what lets a
 * launcher read other apps' sessions); without it the list is just empty.
 */
class MediaHub(private val context: Context) {
    private val _players = MutableStateFlow<List<Player>>(emptyList())
    /** Playing first, then the most recently active paused ones. */
    val players: StateFlow<List<Player>> = _players

    private val main = Handler(Looper.getMainLooper())
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NotificationFeedService::class.java)
    private val watched = HashMap<MediaSession_Token, Pair<MediaController, MediaController.Callback>>()
    private var started = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list.orEmpty()) }

    /** (Re)attaches; cheap to call on every resume — e.g. right after notification access is granted. */
    fun start() {
        if (!NotificationFeed.hasAccess(context)) return
        runCatching {
            if (!started) { msm.addOnActiveSessionsChangedListener(sessionsChanged, listener, main); started = true }
            track(msm.getActiveSessions(listener))
        }
    }

    fun toggle(p: Player) = runCatching { if (p.playing) p.controller.transportControls.pause() else p.controller.transportControls.play() }
    fun next(p: Player) = runCatching { p.controller.transportControls.skipToNext() }
    fun previous(p: Player) = runCatching { p.controller.transportControls.skipToPrevious() }
    /** The player's own screen (what tapping its notification would open). */
    fun open(p: Player): Boolean = p.controller.sessionActivity?.let { PendingIntents.sendFromForeground(context, it) }
        ?: context.packageManager.getLaunchIntentForPackage(p.pkg)?.let {
            runCatching { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        } ?: false

    private fun track(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in tokens }.forEach { t -> watched.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        list.forEach { c ->
            if (c.sessionToken in watched) return@forEach
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onSessionDestroyed() = publish()
            }
            c.registerCallback(cb, main)
            watched[c.sessionToken] = c to cb
        }
        publish()
    }

    private fun publish() {
        _players.value = watched.values.mapNotNull { (c, _) -> toPlayer(c) }
            .sortedWith(compareByDescending<Player> { it.playing }.thenByDescending { it.updatedAt })
    }

    private fun toPlayer(c: MediaController): Player? {
        val md = c.metadata ?: return null
        val st = c.playbackState
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val state = st?.state ?: PlaybackState.STATE_NONE
        if (state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_ERROR) return null
        val actions = st?.actions ?: 0L
        return Player(
            id = c.sessionToken.hashCode().toString() + c.packageName,
            pkg = c.packageName,
            app = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString() }
                .getOrDefault(c.packageName),
            title = title,
            artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))?.takeIf { it.isNotBlank() },
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING,
            position = st?.position ?: 0L,
            updatedAt = st?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            controller = c,
        )
    }
}

private typealias MediaSession_Token = android.media.session.MediaSession.Token
