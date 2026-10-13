// 그래도 재생되는 영상 광고는 끝으로 넘기고 '건너뛰기' 버튼을 누른다.
(function () {
  setInterval(() => {
    const ad = document.querySelector(".ad-showing, .ad-interrupting");
    if (!ad) return;
    const v = ad.querySelector("video") || document.querySelector("video");
    if (v && isFinite(v.duration) && v.duration > 0) {
      v.muted = true;
      v.currentTime = v.duration;
    }
    const skip = document.querySelector(
      ".ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, button[class*='skip']"
    );
    if (skip) skip.click();
  }, 400);
})();
