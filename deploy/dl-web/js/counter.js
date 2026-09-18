/* counter —— 数字滚动：把带 .count 且 data-count 的文本从 0 滚到目标值。
 * 减弱动态时直接置终值，不做滚动。 */
(function () {
  var M = window.PACC_MOTION;
  if (!M) return;

  var els = document.querySelectorAll('.count[data-count]');
  els.forEach(function (el) {
    var target = parseFloat(el.getAttribute('data-count'));
    if (isNaN(target)) return;
    var obj = { v: 0 };
    var fmt = el.getAttribute('data-fmt'); // 例如 '2' 保留两位
    var decimals = fmt ? parseInt(fmt, 10) : 0;

    if (M.reduced) {
      el.textContent = target.toFixed(decimals);
      return;
    }

    gsap.to(obj, {
      v: target,
      duration: M.dur(900),
      ease: 'power2.out',
      onUpdate: function () { el.textContent = obj.v.toFixed(decimals); },
    });
  });

  // 通用 numbers 滚动用于数据密集区（仅当需要时由对应脚本自行调用）
  window.PACC_MOTION.countUp = function (el, target, decimals) {
    var obj = { v: 0 };
    if (M.reduced) { el.textContent = String(target); return obj; }
    return gsap.to(obj, {
      v: target, duration: M.dur(800), ease: 'power2.out',
      onUpdate: function () { el.textContent = obj.v.toFixed(decimals || 0); },
    });
  };
})();