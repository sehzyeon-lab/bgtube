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
    private const val ADBLOCK_URI = "resource://android/assets/adblock/"
    private const val ADBLOCK_ID = "adblock@bgtube.app"

    private var runtime: GeckoRuntime? = null
    private var main: GeckoSession? = null
    /** Playback.media 를 소유한(= 마지막으로 재생을 시작한) 세션. */
    private var mediaOwner: GeckoSession? = null

    var canGoBack = false
    var onFullScreen: ((Boolean) -> Unit)? = null
    var onSessionLost: (() -> Unit)? = null
    var onPlaying: ((Boolean) -> Unit)? = null
    /** 현재 페이지 URL 이 바뀌었을 때 (유튜브는 SPA 라 history API 이동도 포함). */
    var onUrl: ((String) -> Unit)? = null
    var currentUrl: String = START_URL
        private set
    private var history: GeckoSession.HistoryDelegate.HistoryList? = null

    private fun isWatch(url: String) = android.net.Uri.parse(url).path?.startsWith("/watch") == true

    /**
     * 공식 앱처럼: 영상 화면에서 뒤로가기 → 영상에 들어오기 전 화면(홈/검색/구독 등)으로.
     * 자동재생·추천으로 이어 본 영상들은 건너뛴다.
     */
    fun backFromVideo(context: Context): Boolean {
        if (!isWatch(currentUrl)) return false
        val s = session(context)
        history?.let { h ->
            for (i in h.currentIndex - 1 downTo 0) {
                if (!isWatch(h[i].uri)) {
                    s.gotoHistoryIndex(i)
                    return true
                }
            }
        }
        s.loadUri(START_URL)
        return true
    }

    fun session(context: Context): GeckoSession = main ?: create(context, START_URL).also { main = it }

    fun loadUrl(context: Context, url: String) = session(context).loadUri(url)

    private fun create(context: Context, url: String): GeckoSession {
        val app = context.applicationContext
        val rt = runtime ?: GeckoRuntime.create(app).also {
            runtime = it
            // 페이지가 숨김 상태가 되지 않도록 visibility API 를 덮어쓰는 내장 확장.
            it.webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
            // 광고 차단 (플레이어 응답에서 광고 정보 제거)
            it.webExtensionController.ensureBuiltIn(ADBLOCK_URI, ADBLOCK_ID)
        }
        val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(false).build())
        installDelegates(app, s)
        s.open(rt)
        s.loadUri(url)
        return s
    }

    private fun installDelegates(app: Context, s: GeckoSession) {
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (session === main) this@Browser.canGoBack = canGoBack
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (session === main && url != null) {
                    currentUrl = url
                    onUrl?.invoke(url)
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

        s.promptDelegate = object : GeckoSession.PromptDelegate {
            // 유튜브의 '공유' 버튼(navigator.share)을 안드로이드 기본 공유창으로 연결
            override fun onSharePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.SharePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val body = prompt.uri ?: prompt.text ?: ""
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                prompt.title?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
                return try {
                    app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    GeckoResult.fromValue(prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.SUCCESS))
                } catch (e: Exception) {
                    Log.w(TAG, "share", e)
                    GeckoResult.fromValue(prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.FAILURE))
                }
            }
        }

        s.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onHistoryStateChange(
                session: GeckoSession,
                historyList: GeckoSession.HistoryDelegate.HistoryList,
            ) {
                if (session === main) history = historyList
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
        if (s === main) {
            main = null
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
