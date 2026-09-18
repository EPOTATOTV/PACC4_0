/* cursor-glow —— 桌面指针光斑：跟随鼠标的微弱暖光，只影响背景，不影响前台。
 * 减弱动态时直接隐藏；不影响可访问性（纯装饰，aria-hidden）。 */
(function () {
  var M = window.PACC_MOTION;
  if (!M || M.reduced || M.tiny) return;
  if (window.matchMedia && !window.matchMedia('(pointer: fine)').matches) return;

  var glow = document.createElement('div');
  glow.setAttribute('aria-hidden', 'true');
  glow.className = 'pacc-cursor-glow';
  document.body.appendChild(glow);

  var x = window.innerWidth / 2;
  var y = -160; // 初始在视口上方，避免首屏正中突兀出现
  gsap.set(glow, { x: x, y: y, opacity: 0 });

  var idle = gsap.to(glow, { opacity: 0.55, duration: M.dur(2400), ease: 'sine.inOut' });
  var ghost = gsap.to(glow, { x: x + 40, y: y + 40, duration: M.dur(5200), ease: 'none', repeat: -1, yoyo: true, paused: true });

  document.addEventListener('mousemove', function (e) {
    ghost.pause();
    gsap.to(glow, {
      x: e.clientX, y: e.clientY, duration: M.dur(300), ease: 'power2.out', overwrite: 'auto',
    });
    clearTimeout(glow._t);
    glow._t = setTimeout(function () {
      gsap.to(glow, { opacity: 0.28, duration: M.dur(600), ease: 'power2.out' });
      ghost.play();
    }, 900);
  });
  document.addEventListener('mouseleave', function () {
    gsap.to(glow, { opacity: 0, duration: M.dur(520) });
  });
})();