/* main —— 动效总装配：检测通过后才进入各模块。
 * 模块按加载顺序各自自检测 window.PACC_MOTION；此处负责最后的清理兜底。 */
(function () {
  var M = window.PACC_MOTION;
  if (!M) return;

  // onload 后再挂 ScrollTrigger 刷新，避免布局尺寸变动影响触发器
  window.addEventListener('load', function () {
    if (typeof ScrollTrigger !== 'undefined') ScrollTrigger.refresh();
  });

  // 低端/减弱：确认正文动效元素回到可见终态（按钮走 hero.js 的快速淡出，不在兜底范围内）
  if (M.reduced || M.tiny) {
    document.querySelectorAll('.card, .reveal, .hero-eyebrow, .hero-sub, .hero-meta')
      .forEach(function (el) { el.style.opacity = '1'; });
  }

  // 下载计数信标：命中下载链接时静默上报（同源 /api/dl/track，best-effort）
  var platformMap = { win: 'WIN', probe: 'JVM' };
  document.addEventListener('click', function (e) {
    var a = e.target.closest('a.btn[href^="files/"]');
    if (!a) return;
    var file = (a.getAttribute('download') || a.getAttribute('href') || '');
    var platform = platformMap[a.dataset.file] || guessPlatform(file);
    var artifact = /jar/i.test(file) ? 'probe' : 'client';
    try {
      navigator.sendBeacon('/api/dl/track', new Blob(
        [JSON.stringify({ platform: platform, artifact: artifact })],
        { type: 'application/json' }
      ));
    } catch (x) { /* 静默 */ }
  });

  function guessPlatform(file) {
    if (/windows/i.test(file)) return 'WIN';
    if (/linux/i.test(file)) return 'LNX';
    if (/android|\.apk/i.test(file)) return 'APK';
    if (/ios|ipad/i.test(file)) return 'IOS';
    if (/harmony/i.test(file)) return 'HMY';
    return 'UNK';
  }
})();