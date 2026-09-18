/* PACC motion config —— 所有动效的统一开关、降级与时长基准。
 * 尊重系统减弱动态；移动端抽稀粒子并放缓滚动锚定。低端环境直接关掉 JS 动效。
 */
(function () {
  var mq = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  var reduced = !!(mq && mq.matches);

  // 低端设备：内存/核心少时放弃 ScrollTrigger 与粒子，只留入场淡入
  var tiny = navigator.hardwareConcurrency && navigator.hardwareConcurrency <= 4;

  function isMobile() {
    return window.innerWidth < 720 || (window.matchMedia && window.matchMedia('(pointer: coarse)').matches);
  }

  /* 时间基准：标准时长为基准毫秒；减弱动态时额外压到极短，视觉上几乎为零 */
  function dur(ms) {
    if (reduced) return Math.min(ms * 0.12, 100);
    return ms / 1000;
  }

  gsap.registerPlugin(ScrollTrigger, ScrollToPlugin);

  window.PACC_MOTION = {
    reduced: reduced,
    tiny: tiny,
    isMobile: isMobile,
    dur: dur,
    eases: {
      in: 'power3.out',
      inSoft: 'power2.out',
      back: 'back.out(1.8)',
      expo: 'expo.out',
    },
  };
})();