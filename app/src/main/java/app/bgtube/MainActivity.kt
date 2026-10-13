package app.bgtube

import android.Manifest
import android.content.Intent
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.mozilla.geckoview.GeckoView

class MainActivity : AppCompatActivity() {
    private lateinit var geckoView: GeckoView
    private var fullscreen = false
    private lateinit var navBar: View
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var navDivider: View
    private var night = false

    private enum class Tab(val key: String, val url: String) {
        HOME("home", "https://m.youtube.com/"),
        SHORTS("shorts", "https://m.youtube.com/shorts"),
        SUBS("subs", "https://m.youtube.com/feed/subscriptions"),
        YOU("you", "https://m.youtube.com/feed/you"),
    }
    private var selected = Tab.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        geckoView = findViewById(R.id.gecko)
        navBar = findViewById(R.id.navbar)
        refresh = findViewById(R.id.refresh)
        refresh.setColorSchemeColors(0xFFFF0033.toInt())
        // 페이지가 맨 위일 때만 당겨서 새로고침. Shorts(아래로 끌기 = 이전 Shorts)와 전체화면에서는 끔.
        refresh.setOnChildScrollUpCallback { _, _ ->
            fullscreen || Browser.scrollY > 0 || android.net.Uri.parse(Browser.currentUrl).path?.startsWith("/shorts") == true
        }
        refresh.setOnRefreshListener {
            Browser.session(this).reload()
            refresh.postDelayed({ refresh.isRefreshing = false }, 8000) // 안전장치
        }
        Browser.onPageStop = { runOnUiThread { refresh.isRefreshing = false } }
        navDivider = findViewById(R.id.nav_divider)
        for (tab in Tab.values()) {
            findViewById<View>(navId(tab)).setOnClickListener {
                selected = tab
                paintNav()
                Browser.loadUrl(this, tab.url)
            }
        }
        Browser.onUrl = { url -> runOnUiThread { tabFor(url)?.let { selected = it; paintNav() } } }
        tabFor(Browser.currentUrl)?.let { selected = it }

        applySystemBarTheme()

        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val session = Browser.session(this@MainActivity)
                if (fullscreen) session.exitFullScreen() // 전체화면이면 먼저 전체화면 해제
                else if (Browser.backFromVideo(this@MainActivity)) { /* 영상 이전 화면으로 */ }
                else if (Browser.canGoBack) session.goBack()
                else moveTaskToBack(true) // 종료하지 않고 백그라운드로 보내 재생 유지
            }
        })

        Browser.onFullScreen = ::setFullscreen
        Browser.onSessionLost = { runOnUiThread { refreshViews() } }
        Browser.onPlaying = { playing ->
            runOnUiThread {
                // 공식 유튜브처럼 재생 중에는 화면이 꺼지지 않게 한다.
                if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                updatePipParams()
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        refreshViews()
        handleIntent(intent)
        UpdateChecker.check(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { Browser.loadUrl(this, it.toString()) }
            // 다른 앱에서 '공유'로 보낸 유튜브 링크 열기
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { Regex("https?://\\S+").find(it)?.value }
                ?.takeIf { it.contains("youtu") }
                ?.let { Browser.loadUrl(this, it) }
        }
    }

    /** 세션을 GeckoView 에 (다시) 붙인다. */
    private fun refreshViews() {
        geckoView.releaseSession()
        geckoView.setSession(Browser.session(this))
    }

    override fun onResume() {
        super.onResume()
        UpdateChecker.onResume(this)
    }

    // 화면 꺼짐/백그라운드에서도 페이지를 '활성' 상태로 유지(visibility 우회의 네이티브 쪽).
    override fun onPause() {
        super.onPause()
        keepActive()
    }

    override fun onStop() {
        super.onStop()
        keepActive()
    }

    private fun keepActive() {
        Browser.session(this).setActive(true)
    }

    private fun navId(t: Tab) = resources.getIdentifier("nav_${t.key}", "id", packageName)

    /** URL 로 어느 탭인지 판단. 영상 화면 등은 null (선택 유지). */
    private fun tabFor(url: String): Tab? {
        val path = android.net.Uri.parse(url).path ?: "/"
        return when {
            path.startsWith("/shorts") -> Tab.SHORTS
            path.startsWith("/feed/subscriptions") -> Tab.SUBS
            path.startsWith("/feed/you") || path.startsWith("/feed/library") ||
                path.startsWith("/feed/history") || path.startsWith("/playlist") -> Tab.YOU
            path == "/" || path.isEmpty() || path.startsWith("/feed/trending") -> Tab.HOME
            else -> null
        }
    }

    private fun paintNav() {
        val fg = if (night) 0xFFFFFFFF.toInt() else 0xFF0F0F0F.toInt()
        val dim = if (night) 0x99FFFFFF.toInt() else 0x990F0F0F.toInt()
        val bg = if (night) 0xFF0F0F0F.toInt() else 0xFFFFFFFF.toInt()
        navBar.setBackgroundColor(bg)
        navDivider.setBackgroundColor(if (night) 0x33FFFFFF else 0x22000000)
        for (tab in Tab.values()) {
            val on = tab == selected
            val c = if (on) fg else dim
            findViewById<ImageView>(resources.getIdentifier("nav_${tab.key}_icon", "id", packageName))
                .setColorFilter(c)
            findViewById<TextView>(resources.getIdentifier("nav_${tab.key}_label", "id", packageName)).apply {
                setTextColor(c)
                paint.isFakeBoldText = on
                invalidate()
            }
        }
    }

    private fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        navBar.visibility = if (enabled) View.GONE else View.VISIBLE
        navDivider.visibility = navBar.visibility
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        // 전체화면이면 가로로, 해제되면 기기 방향을 따른다 (공식 앱 동작).
        requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = if (enabled)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        updatePipParams()
    }

    // 전체화면으로 영상 재생 중일 때 홈으로 나가면 작은 창(PiP)으로 전환한다.
    private fun updatePipParams() {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(fullscreen && Playback.playing)
        try { setPictureInPictureParams(b.build()) } catch (e: Exception) { android.util.Log.w("BgTube", "pip", e) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && fullscreen && Playback.playing) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                )
            } catch (e: Exception) { android.util.Log.w("BgTube", "pip", e) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarTheme()
    }

    /** 기기 다크/라이트 모드에 맞춰 배경과 상태바·내비게이션 바 아이콘 색을 맞춘다 (유튜브 화면과 이어 보이게). */
    private fun applySystemBarTheme() {
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val color = if (night) 0xFF0F0F0F.toInt() else 0xFFFFFFFF.toInt()
        findViewById<View>(R.id.root).setBackgroundColor(color)
        window.decorView.setBackgroundColor(color)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = color
            window.navigationBarColor = color
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        paintNav()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    override fun onDestroy() {
        Browser.onFullScreen = null
        Browser.onSessionLost = null
        Browser.onPlaying = null
        Browser.onUrl = null
        Browser.onPageStop = null
        geckoView.releaseSession()
        super.onDestroy()
    }
}
