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
