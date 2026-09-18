/* scroll —— 页内锚点平滑锚定 + 章节滚入淡出 + 导航收束态。
 * 用 GSAP ScrollToPlugin 统一锚点行为;减弱动态时退化为原生跳转。 */
(function () {
  var M = window.PACC_MOTION;
  if (!M) return;

  // 锚点链接平滑锚定（在本页内）
  document.addEventListener('click', function (e) {
    var a = e.target.closest('a[href^="#"]');
    if (!a) return;
    var id = a.getAttribute('href');
    var target = document.querySelector(id);
    if (!target) return;
    if (M.reduced) return; // 直接原生跳转
    e.preventDefault();
    var y = Math.max(0, target.getBoundingClientRect().top + window.scrollY - 64);
    gsap.to(window, { scrollTo: { y: y }, duration: M.dur(620), ease: M.eases.inOut || 'power3.inOut', overwrite: 'auto' });
  });

  // 章节滚入：给定 .reveal 的元素从下方浮现。ScrollTrigger 仅在非减弱/非低端启用
  var revealEls = document.querySelectorAll('.reveal');
  if (M.reduced || M.tiny) {
    revealEls.forEach(function (el) { gsap.set(el, { opacity: 1 }); });
    return;
  }
  revealEls.forEach(function (el, i) {
    gsap.from(el, {
      y: 52,
      opacity: 0,
      duration: M.dur(680),
      ease: M.eases.expo,
      scrollTrigger: { trigger: el, start: 'top 90%', once: true },
    });
  });

  // 导航收束：滚动超过 hero 后加 .scrolled 类（纯样式副作用，无 layout 抖动由 CSS 处理）
  var nav = document.querySelector('.nav');
  if (nav) {
    ScrollTrigger.create({
      start: 'top -60',
      onToggle: function (self) { nav.classList.toggle('scrolled', self.isActive); },
    });
  }
})();