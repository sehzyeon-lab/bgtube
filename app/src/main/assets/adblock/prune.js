// 유튜브 플레이어 응답에서 광고 정보를 제거한다 (uBlock Origin 의 json-prune 과 같은 방식).
// 페이지 컨텍스트(MAIN world)에서 실행된다.
(() => {
  "use strict";
  const KEYS = ["adPlacements", "playerAds", "adSlots", "adBreakHeartbeatParams"];
  const prune = (o) => {
    try {
      if (!o || typeof o !== "object") return o;
      for (const k of KEYS) if (k in o) delete o[k];
      if (o.playerResponse && typeof o.playerResponse === "object") prune(o.playerResponse);
      if (Array.isArray(o)) for (const x of o) prune(x);
    } catch (e) {}
    return o;
  };

  // 1) JSON.parse 결과
  const parse = JSON.parse;
  JSON.parse = function () {
    return prune(parse.apply(this, arguments));
  };

  // 2) fetch 응답의 .json()
  const json = Response.prototype.json;
  Response.prototype.json = function () {
    return json.call(this).then(prune);
  };

  // 3) 첫 페이지에 박혀 오는 ytInitialPlayerResponse
  let initial;
  try {
    Object.defineProperty(window, "ytInitialPlayerResponse", {
      configurable: true,
      get() { return initial; },
      set(v) { initial = prune(v); },
    });
  } catch (e) {}
})();
