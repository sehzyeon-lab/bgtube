package app.bgtube

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.MediaSession

/**
 * GeckoRuntime/GeckoSession 을 액티비티 수명과 분리해 앱 프로세스에 붙들어 둔다.
 * 화면이 꺼지거나 액티비티가 멈춰도 세션(=재생)이 계속 살아 있게 하기 위함.
 */
object Browser {
    const val START_URL = "https://m.youtube.com/"
    private const val TAG = "BgTube"
    private const val EXTENSION_URI = "resource://android/assets/extension/"
    private const val EXTENSION_ID = "bgplay@bgtube.app"

    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null

    var canGoBack = false
    var onFullScreen: ((Boolean) -> Unit)? = null
    var onSessionLost: (() -> Unit)? = null
    var onPlaying: ((Boolean) -> Unit)? = null

    fun session(context: Context): GeckoSession {
        session?.let { return it }
        val app = context.applicationContext
        val rt = runtime ?: GeckoRuntime.create(app).also {
            runtime = it
            // 페이지가 숨김 상태가 되지 않도록 visibility API 를 덮어쓰는 내장 확장.
            it.webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
        }
        val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(false).build())
        installDelegates(app, s)
        s.open(rt)
        s.loadUri(START_URL)
        session = s
        return s
    }

    fun loadUrl(context: Context, url: String) = session(context).loadUri(url)

    private fun installDelegates(app: Context, s: GeckoSession) {
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@Browser.canGoBack = canGoBack
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                val scheme = request.uri.substringBefore(':').lowercase()
                val ok = scheme in setOf("http", "https", "about", "blob", "data", "javascript")
                return GeckoResult.fromValue(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
        }

        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                onFullScreen?.invoke(fullScreen)
            }

            override fun onCrash(session: GeckoSession) = lost(session)
            override fun onKill(session: GeckoSession) = lost(session)
        }

        s.setMediaSessionDelegate(object : MediaSession.Delegate {
            override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
                Playback.media = mediaSession
                startService(app)
                Playback.notifyChanged()
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                Playback.reset()
                onPlaying?.invoke(Playback.playing)
                Playback.notifyChanged()
            }

            override fun onMetadata(
                session: GeckoSession,
                mediaSession: MediaSession,
                meta: MediaSession.Metadata,
            ) {
                Playback.title = meta.title
                Playback.artist = meta.artist
                Playback.album = meta.album
                Playback.artwork = null
                Playback.notifyChanged()
                try {
                    meta.artwork?.getBitmap(256)?.accept { bmp ->
                        Playback.artwork = bmp
                        Playback.notifyChanged()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "artwork", e)
                }
            }

            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                Playback.media = mediaSession
                Playback.playing = true
                startService(app)
                onPlaying?.invoke(Playback.playing)
                Playback.notifyChanged()
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                Playback.playing = false
                onPlaying?.invoke(Playback.playing)
                Playback.notifyChanged()
            }

            override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
                Playback.playing = false
                onPlaying?.invoke(Playback.playing)
                Playback.notifyChanged()
            }

            override fun onPositionState(
                session: GeckoSession,
                mediaSession: MediaSession,
                state: MediaSession.PositionState,
            ) {
                Playback.durationSec = state.duration
                Playback.positionSec = state.position
                Playback.rate = state.playbackRate
                Playback.notifyChanged()
            }
        })
    }

    private fun lost(s: GeckoSession) {
        Log.w(TAG, "GeckoSession lost (crash/kill)")
        Playback.reset()
        Playback.notifyChanged()
        s.close()
        session = null
        canGoBack = false
        onSessionLost?.invoke()
    }

    private fun startService(app: Context) {
        try {
            ContextCompat.startForegroundService(app, Intent(app, MediaService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "cannot start media service", e)
        }
    }
}
