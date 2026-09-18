/* background —— 背景纵深与光斑：降低滚动视差、hero 光斑长周期漂移、轻量粒子。
 * 只做纵深，不抢前台内容；减弱动态时全部收成静态。 */
(function () {
  var M = window.PACC_MOTION;
  if (!M || M.reduced || M.tiny) return;

  var bg = document.querySelector('.bg');
  var heroImg = document.querySelector('.hero-img');
  if (!bg) return;

  var parallax = M.isMobile() ? 0.05 : 0.16;

  if (heroImg) {
    gsap.to(heroImg, {
      y: function () { return -window.innerHeight * parallax; },
      ease: 'none',
      scrollTrigger: {
        trigger: document.querySelector('.hero'),
        start: 'top top',
        end: 'bottom top',
        scrub: true,
      },
    });
  }

  // 粒子：若干缓慢飘移的细小光点补充纵深。抽稀至移动端一半，每颗用随机相位避免同速进场。
  var count = M.isMobile() ? 10 : 22;
  var wrap = document.createElement('div');
  wrap.setAttribute('aria-hidden', 'true');
  wrap.className = 'pacc-dots';
  document.body.appendChild(wrap);

  for (var i = 0; i < count; i += 1) {
    var d = document.createElement('span');
    var size = 1 + Math.random() * 2.2;
    d.style.width = size + 'px';
    d.style.height = size + 'px';
    d.style.left = (Math.random() * 100) + '%';
    d.style.top = (Math.random() * 100) + '%';
    wrap.appendChild(d);

    var delay = -Math.random() * 24;
    gsap.to(d, {
      y: -(60 + Math.random() * 160),
      x: (Math.random() - 0.5) * 120,
      opacity: 0,
      duration: 22 + Math.random() * 18,
      delay: delay,
      repeat: -1,
      ease: 'sine.inOut',
    });
  }
})();