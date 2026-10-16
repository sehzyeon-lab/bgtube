// 페이지가 항상 '보이는' 상태라고 믿게 만든다 (Page Visibility API 우회).
(function () {
  "use strict";
  try {
    const doc = document.wrappedJSObject;
    const constant = (name, value) => {
      try {
        Object.defineProperty(doc, name, {
          get: exportFunction(() => value, window),
          configurable: true,
        });
      } catch (e) {}
    };
    constant("hidden", false);
    constant("webkitHidden", false);
    constant("visibilityState", "visible");
    constant("webkitVisibilityState", "visible");
    doc.hasFocus = exportFunction(() => true, window);
  } catch (e) {}

  // 사이트가 visibilitychange 로 일시정지하지 못하도록 이벤트를 먼저 삼킨다.
  const swallow = (e) => e.stopImmediatePropagation();
  for (const type of ["visibilitychange", "webkitvisibilitychange"]) {
    window.addEventListener(type, swallow, true);
    document.addEventListener(type, swallow, true);
  }
  // 창 단위 blur 만 삼킨다(입력창 blur 는 그대로 둔다).
  window.addEventListener("blur", (e) => {
    if (e.target === window || e.target === document) e.stopImmediatePropagation();
  }, true);

  // 다음/이전 트랙: 유튜브가 단일 영상에서는 핸들러를 등록하지 않으므로 대체 동작을 직접 등록한다.
  //  - 다음: 영상 끝으로 이동 → 유튜브의 자동재생이 다음 영상을 불러온다(자동재생이 켜져 있어야 함)
  //  - 이전: 5초 넘게 재생했으면 처음부터, 아니면 뒤로 가기
  try {
    const ms = navigator.wrappedJSObject.mediaSession;
    const origSet = ms.setActionHandler;
    const video = () => document.querySelector("video");
    const fallback = {
      nexttrack: exportFunction(function () {
        const v = video();
        if (v && isFinite(v.duration)) v.currentTime = v.duration;
      }, window),
      previoustrack: exportFunction(function () {
        const v = video();
        if (v && v.currentTime > 5) v.currentTime = 0;
        else history.back();
      }, window),
    };
    ms.setActionHandler = exportFunction(function (action, handler) {
      if ((action === "nexttrack" || action === "previoustrack") && !handler) {
        handler = fallback[action];
      }
      return origSet.call(ms, action, handler);
    }, window);
    origSet.call(ms, "nexttrack", fallback.nexttrack);
    origSet.call(ms, "previoustrack", fallback.previoustrack);
  } catch (e) {}
})();

// 미니 플레이어/PiP: 뷰포트가 작고 가로로 긴 16:9 이면 영상만 보이게 하는 클래스를 켠다.
(function () {
  const root = document.documentElement;
  const check = () => {
    if (!root) return;
    const mini = innerWidth <= 380 && innerHeight > 0 && innerWidth / innerHeight > 1.4;
    root.classList.toggle("bgtube-mini", mini);
  };
  addEventListener("resize", check);
  document.addEventListener("DOMContentLoaded", check);
  check();
})();

// 모바일 웹의 '앱 열기' 버튼/배너를 숨긴다. (상단 바/배너만 검사해 피드 스크롤 부담을 줄임)
(function () {
  const KEYS = ["앱 열기", "앱에서 열기", "앱으로 열기", "앱으로 보기", "YouTube 앱", "Open app", "Open App", "Open in app", "OPEN APP", "Get the app"];
  const SCOPE = "ytm-mobile-topbar-renderer a, ytm-mobile-topbar-renderer button, header a, header button, " +
    "[class*='promo'] a, [class*='promo'] button, [class*='banner'] a, [class*='banner'] button";
  const hide = (all) => {
    for (const el of document.querySelectorAll(all ? "a, button" : SCOPE)) {
      const t = (el.textContent || "").trim();
      if (t && t.length <= 20 && KEYS.some((k) => t.includes(k))) el.style.display = "none";
    }
  };
  let timer = 0;
  const schedule = () => {
    if (timer) return;
    timer = setTimeout(() => { timer = 0; hide(false); }, 1000);
  };
  document.addEventListener("DOMContentLoaded", () => {
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    hide(true); // 처음 한 번만 전체 검사
  });
})();


// AV1 을 지원하지 않는다고 알려 유튜브가 하드웨어 디코딩되는 VP9/H.264 를 주게 한다
// (AV1 소프트웨어 디코딩은 시작이 느리고 배터리를 많이 쓴다).
(function () {
  try {
    const w = window.wrappedJSObject;
    const isAv1 = (t) => /av01|av1/i.test(String(t || ""));
    const MS = w.MediaSource;
    if (MS && MS.isTypeSupported) {
      const orig = MS.isTypeSupported;
      MS.isTypeSupported = exportFunction(function (t) {
        return isAv1(t) ? false : orig.call(MS, t);
      }, window);
    }
    const proto = w.HTMLMediaElement && w.HTMLMediaElement.prototype;
    if (proto && proto.canPlayType) {
      const origCan = proto.canPlayType;
      proto.canPlayType = exportFunction(function (t) {
        return isAv1(t) ? "" : origCan.call(this, t);
      }, window);
    }
  } catch (e) {}
})();

// 오래 백그라운드에 있다가 돌아오면 영상 주소가 만료돼 '오류가 발생했습니다(재생 ID …)'가 뜬다.
// 이 오류가 보이면 보던 위치(t=)를 붙여 페이지를 다시 불러온다. 같은 영상은 1분에 한 번만 시도.
(function () {
  const ERR = /재생 ID|playback ID|오류가 발생했습니다|An error occurred/i;
  const videoId = () => new URLSearchParams(location.search).get("v");
  setInterval(() => {
    const id = videoId();
    if (!id || !location.pathname.startsWith("/watch")) return;
    const v = document.querySelector("video");
    if (v && v.currentTime > 1) {
      try { sessionStorage.setItem("bgtube-t-" + id, String(Math.floor(v.currentTime))); } catch (e) {}
    }
    let found = false;
    for (const el of document.querySelectorAll("[class*='error']")) {
      if (ERR.test(el.textContent || "")) { found = true; break; }
    }
    if (!found) return;
    try {
      const key = "bgtube-retry-" + id;
      if (Date.now() - (+sessionStorage.getItem(key) || 0) < 60000) return;
      sessionStorage.setItem(key, String(Date.now()));
      const t = sessionStorage.getItem("bgtube-t-" + id);
      const u = new URL(location.href);
      if (t) u.searchParams.set("t", t + "s");
      location.replace(u.toString());
    } catch (e) {}
  }, 2000);
})();

// 유튜브는 한동안 조작이 없으면 '동영상이 일시중지되었습니다. 계속 시청하시겠습니까?'를 띄우고 멈춘다.
// 백그라운드에서는 이 창이 안 보여 그냥 끊긴 것처럼 느껴진다.
//  - 마지막 활동 시각(_lact)을 주기적으로 갱신해 질문 자체가 안 나오게 하고
//  - 그래도 나오면 '예/계속'을 눌러 재생을 이어간다.
(function () {
  const w = window.wrappedJSObject;
  setInterval(() => { try { w._lact = Date.now(); } catch (e) {} }, 60000);

  const ASK = /계속 시청|일시중지되었습니다|Continue watching|Video paused/i;
  const YES = /^(예|네|계속|Yes|Continue)/i;
  setInterval(() => {
    for (const dlg of document.querySelectorAll(
      "ytm-confirm-dialog-renderer, yt-confirm-dialog-renderer, tp-yt-paper-dialog, [role='dialog'], dialog"
    )) {
      if (!ASK.test(dlg.textContent || "")) continue;
      for (const b of dlg.querySelectorAll("button, [role='button']")) {
        if (YES.test((b.textContent || "").trim())) { b.click(); break; }
      }
      const v = document.querySelector("video");
      if (v && v.paused) { try { v.play(); } catch (e) {} }
    }
  }, 3000);
})();
