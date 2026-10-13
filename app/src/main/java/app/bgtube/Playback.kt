package app.bgtube

import android.graphics.Bitmap
import org.mozilla.geckoview.MediaSession

/** 웹 페이지의 MediaSession 상태를 서비스/알림과 공유하는 단일 저장소. */
object Playback {
    var media: MediaSession? = null
    var playing = false
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var artwork: Bitmap? = null
    var durationSec = -1.0
    var positionSec = 0.0
    var rate = 1.0

    /** 상태가 바뀔 때마다 호출되는 서비스 쪽 콜백. */
    var onChanged: (() -> Unit)? = null

    fun reset() {
        media = null
        playing = false
        title = null
        artist = null
        album = null
        artwork = null
        durationSec = -1.0
        positionSec = 0.0
        rate = 1.0
    }

    fun notifyChanged() = onChanged?.invoke()
}
