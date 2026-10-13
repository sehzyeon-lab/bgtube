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
})();
