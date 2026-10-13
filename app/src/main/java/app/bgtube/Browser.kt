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
 *
 * 미니 플레이어: 영상을 재생하던 세션을 그대로 작은 창(mini)으로 내리고,
 * 화면에는 새 세션(main)을 띄워 이전 페이지를 보여 준다.
 */
object Browser {
    const val START_URL = "https://m.youtube.com/"
    private const val TAG = "BgTube"
    private const val EXTENSION_URI = "resource://android/assets/extension/"
    private const val EXTENSION_ID = "bgplay@bgtube.app"

    private var runtime: GeckoRuntime? = null
    private var main: GeckoSession? = null
    private var mini: GeckoSession? = null
    /** Playback.media 를 소유한(= 마지막으로 재생을 시작한) 세션. */
    private var mediaOwner: GeckoSession? = null
    private val urlHistory = ArrayList<String>()

    var canGoBack = false
    var onFullScreen: ((Boolean) -> Unit)? = null
    var onSessionLost: (() -> Unit)? = null
    var onPlaying: ((Boolean) -> Unit)? = null
    /** main/mini 세션 구성이 바뀌었을 때(화면에 다시 붙여야 함). */
    var onLayoutChanged: (() -> Unit)? = null

    val currentUrl: String get() = urlHistory.lastOrNull() ?: START_URL

    fun session(context: Context): GeckoSession = main ?: create(context, START_URL).also { main = it }

    fun miniSession(): GeckoSession? = mini

    fun loadUrl(context: Context, url: String) = session(context).loadUri(url)

    private fun isWatch(url: String) = url.contains("/watch") || url.contains("/shorts/")

    private fun create(context: Context, url: String): GeckoSession {
        val app = context.applicationContext
        val rt = runtime ?: GeckoRuntime.create(app).also {
            runtime = it
            // 페이지가 숨김 상태가 되지 않도록 visibility API 를 덮어쓰는 내장 확장.
            it.webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
        }
        val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(false).build())
        installDelegates(app, s)
        s.open(rt)
        s.loadUri(url)
        return s
    }

    /** 재생 중인 영상 페이지에서 뒤로가기를 누르면 작은 창으로 내린다. */
    fun enterMini(context: Context): Boolean {
        val playing = main ?: return false
        if (mini != null || !Playback.playing || !isWatch(currentUrl)) return false
        val previous = urlHistory.dropLast(1).lastOrNull { !isWatch(it) } ?: START_URL
        mini = playing
        urlHistory.clear()
        canGoBack = false
        main = create(context, previous)
        onLayoutChanged?.invoke()
        return true
    }

    /** 작은 창을 눌러 다시 크게. 지금 화면의 세션은 닫는다. */
    fun expandMini() {
        val m = mini ?: return
        val old = main
        main = m
        mini = null
        urlHistory.clear()
        canGoBack = true // 이 세션은 영상 이전 페이지를 기록으로 갖고 있다
        old?.close()
        onLayoutChanged?.invoke()
    }

    /** 작은 창 닫기(재생 중이던 영상이면 재생도 중단). */
    fun closeMini() {
        val m = mini ?: return
        mini = null
        if (mediaOwner === m) {
            Playback.reset()
            mediaOwner = null
            onPlaying?.invoke(false)
            Playback.notifyChanged()
        }
        m.close()
        onLayoutChanged?.invoke()
    }

    private fun installDelegates(app: Context, s: GeckoSession) {
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (session === main) this@Browser.canGoBack = canGoBack
            }

            // 유튜브는 SPA 라 페이지 이동이 history API 로 일어난다 → 위치 변경으로 현재 URL 을 추적.
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (session === main && url != null) {
                    if (urlHistory.lastOrNull() != url) urlHistory.add(url)
                    if (urlHistory.size > 50) urlHistory.removeAt(0)
                }
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
                if (session === main) onFullScreen?.invoke(fullScreen)
            }

            override fun onCrash(session: GeckoSession) = lost(session)
            override fun onKill(session: GeckoSession) = lost(session)
        }

        s.setMediaSessionDelegate(object : MediaSession.Delegate {
            private fun owns(session: GeckoSession) = mediaOwner == null || mediaOwner === session

            override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
                if (mini != null && session === main) return // 작은 창의 재생 컨트롤을 유지
                Playback.media = mediaSession
                mediaOwner = session
                startService(app)
                Playback.notifyChanged()
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                if (!owns(session)) return
                Playback.reset()
                mediaOwner = null
                onPlaying?.invoke(Playback.playing)
                Playback.notifyChanged()
            }

            override fun onMetadata(
                session: GeckoSession,
                mediaSession: MediaSession,
                meta: MediaSession.Metadata,
            ) {
                if (!owns(session)) return
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
                // 화면(main)에서 다른 영상을 재생하면 작은 창은 닫는다.
                if (session === main && mini != null) {
                    val m = mini
                    mini = null
                    m?.close()
                    onLayoutChanged?.invoke()
                }
                Playback.media = mediaSession
                mediaOwner = session
                Playback.playing = true
                startService(app)
                onPlaying?.invoke(Playback.playing)
                Playback.notifyChanged()
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                if (!owns(session)) return
                Playback.playing = false
                onPlaying?.invoke(Playback.playing)
                Playback.notifyChanged()
            }

            override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
                if (!owns(session)) return
                Playback.playing = false
                onPlaying?.invoke(Playback.playing)
                Playback.notifyChanged()
            }

            override fun onPositionState(
                session: GeckoSession,
                mediaSession: MediaSession,
                state: MediaSession.PositionState,
            ) {
                if (!owns(session)) return
                Playback.durationSec = state.duration
                Playback.positionSec = state.position
                Playback.rate = state.playbackRate
                Playback.notifyChanged()
            }
        })
    }

    private fun lost(s: GeckoSession) {
        Log.w(TAG, "GeckoSession lost (crash/kill)")
        if (mediaOwner === s) {
            Playback.reset()
            mediaOwner = null
            Playback.notifyChanged()
        }
        s.close()
        if (s === mini) {
            mini = null
        } else if (s === main) {
            main = null
            urlHistory.clear()
            canGoBack = false
        }
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
