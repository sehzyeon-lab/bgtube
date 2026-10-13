package app.bgtube

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.mozilla.geckoview.GeckoView

class MainActivity : AppCompatActivity() {
    private lateinit var geckoView: GeckoView
    private var attached = false
    private lateinit var tabBar: View
    private lateinit var tabVideo: TextView
    private lateinit var tabMusic: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        geckoView = findViewById(R.id.gecko)

        tabBar = findViewById(R.id.tabbar)
        tabVideo = findViewById(R.id.tab_video)
        tabMusic = findViewById(R.id.tab_music)
        tabVideo.setOnClickListener { go(music = false) }
        tabMusic.setOnClickListener { go(music = true) }
        Browser.onUrl = { url -> runOnUiThread { highlight(url.contains("music.youtube.com")) } }
        highlight(Browser.currentUrl.contains("music.youtube.com"))

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (Browser.canGoBack) Browser.session(this@MainActivity).goBack()
                else moveTaskToBack(true) // 종료하지 않고 백그라운드로 보내 재생 유지
            }
        })

        Browser.onFullScreen = ::setFullscreen
        Browser.onSessionLost = { runOnUiThread { attachSession(force = true) } }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        attachSession()
        handleIntent(intent)
        UpdateChecker.check(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW) Browser.loadUrl(this, data.toString())
    }

    private fun go(music: Boolean) {
        highlight(music)
        Browser.loadUrl(this, if (music) Browser.MUSIC_URL else Browser.START_URL)
    }

    private fun highlight(music: Boolean) {
        val on = Color.parseColor("#5EEAD4")
        val off = Color.parseColor("#99FFFFFF")
        tabVideo.setTextColor(if (music) off else on)
        tabMusic.setTextColor(if (music) on else off)
    }

    private fun attachSession(force: Boolean = false) {
        if (attached && !force) return
        if (attached) geckoView.releaseSession()
        geckoView.setSession(Browser.session(this))
        attached = true
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

    private fun keepActive() = Browser.session(this).setActive(true)

    private fun setFullscreen(enabled: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        tabBar.visibility = if (enabled) View.GONE else View.VISIBLE
        if (enabled) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        Browser.onFullScreen = null
        Browser.onUrl = null
        Browser.onSessionLost = null
        if (attached) geckoView.releaseSession()
        super.onDestroy()
    }
}
