package app.bgtube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession as AndroidMediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat

/**
 * 포그라운드 서비스 + 플랫폼 MediaSession.
 * 알림창/잠금화면의 이전·재생/일시정지·다음 버튼을 웹 페이지의 MediaSession 에 연결한다.
 */
class MediaService : Service() {
    private lateinit var session: AndroidMediaSession
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        session = AndroidMediaSession(this, "BgTube").apply {
            setCallback(object : AndroidMediaSession.Callback() {
                override fun onPlay() { Playback.media?.play() }
                override fun onPause() { Playback.media?.pause() }
                override fun onSkipToNext() { Playback.media?.nextTrack() }
                override fun onSkipToPrevious() { Playback.media?.previousTrack() }
                override fun onSeekTo(pos: Long) { Playback.media?.seekTo(pos / 1000.0, false) }
                override fun onStop() { Playback.media?.pause(); shutdown() }
            })
            isActive = true
        }
        Playback.onChanged = ::refresh
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        refresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> Playback.media?.let { if (Playback.playing) it.pause() else it.play() }
            ACTION_NEXT -> Playback.media?.nextTrack()
            ACTION_PREV -> Playback.media?.previousTrack()
            ACTION_CLOSE -> { Playback.media?.pause(); shutdown() }
        }
        return START_NOT_STICKY
    }

    private fun refresh() {
        if (Playback.media == null) {
            shutdown()
            return
        }
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, Playback.title ?: getString(R.string.app_name))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, Playback.artist ?: "")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, Playback.album ?: "")
            .putLong(
                MediaMetadata.METADATA_KEY_DURATION,
                if (Playback.durationSec > 0) (Playback.durationSec * 1000).toLong() else -1L,
            )
        Playback.artwork?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        session.setMetadata(meta.build())

        val state = if (Playback.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP,
                )
                .setState(
                    state,
                    (Playback.positionSec * 1000).toLong(),
                    if (Playback.playing) Playback.rate.toFloat() else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        updateWakeLock(Playback.playing)
    }

    private fun buildNotification(): Notification {
        fun action(icon: Int, title: String, act: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(
                this, act.hashCode(), Intent(this, MediaService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(Playback.title ?: getString(R.string.app_name))
            .setContentText(Playback.artist ?: getString(R.string.notif_idle))
            .setLargeIcon(Playback.artwork)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(Playback.playing)
            .setOnlyAlertOnce(true)
            .addAction(action(R.drawable.ic_prev, getString(R.string.prev), ACTION_PREV))
            .addAction(
                if (Playback.playing) action(R.drawable.ic_pause, getString(R.string.pause), ACTION_PLAY_PAUSE)
                else action(R.drawable.ic_play, getString(R.string.play), ACTION_PLAY_PAUSE),
            )
            .addAction(action(R.drawable.ic_next, getString(R.string.next), ACTION_NEXT))
            .addAction(action(R.drawable.ic_close, getString(R.string.close), ACTION_CLOSE))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun updateWakeLock(playing: Boolean) {
        if (playing) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BgTube:playback")
                    .apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(6 * 60 * 60 * 1000L)
        } else if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun shutdown() {
        updateWakeLock(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (Playback.onChanged != null) Playback.onChanged = null
        updateWakeLock(false)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1
        const val ACTION_PLAY_PAUSE = "app.bgtube.PLAY_PAUSE"
        const val ACTION_NEXT = "app.bgtube.NEXT"
        const val ACTION_PREV = "app.bgtube.PREV"
        const val ACTION_CLOSE = "app.bgtube.CLOSE"
    }
}
