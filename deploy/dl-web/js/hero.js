/* hero —— 首屏入场序列：眉标 → 标题逐字翻起 → 副标题 → 平台/版本 meta → 行动按钮。
 * 各元素错峰 + 自然阻尼;减弱动态时直接呈现终态，不做任何移动。
 * 行动按钮（立即下载 / 运行须知）只在首屏短暂停留后淡出，把下载入口留给导航与下载区。 */
(function () {
  var M = window.PACC_MOTION;
  var hero = document.querySelector('.hero');
  if (!hero || typeof gsap === 'undefined') return;

  // 把标题拆成单字符 span（禁动时仍拆，便于静态免闪）
  var title = hero.querySelector('.hero-title');
  if (title) {
    var text = title.textContent;
    title.textContent = '';
    for (var i = 0; i < text.length; i += 1) {
      var s = document.createElement('span');
      s.className = 'pacc-hero-char';
      s.textContent = text[i];
      title.appendChild(s);
    }
  }

  var actions = hero.querySelectorAll('.hero-actions > *');
  var allHeroEls = hero.querySelectorAll('.hero-eyebrow, .pacc-hero-char, .hero-sub, .hero-meta');

  if (M.reduced || M.tiny) {
    // 正文直接终态；按钮先可见，再做纯透明度淡出（无移动，尊重减弱动态）
    gsap.set(allHeroEls, { opacity: 1 });
    gsap.set(actions, { opacity: 1, y: 0 });
    gsap.to(actions, { opacity: 0, duration: 0.5, delay: 1.2, pointerEvents: 'none' });
    return;
  }

  gsap.set(allHeroEls, { opacity: 0 });
  gsap.set(actions, { opacity: 0 });

  var tl = gsap.timeline({ delay: M.dur(120), defaults: { ease: M.eases.inSoft } });

  tl.to('.hero-eyebrow', { opacity: 1, y: -10, duration: M.dur(460) }, 0);
  tl.fromTo(title.querySelectorAll('.pacc-hero-char'), {
    y: -72, rotateX: -90, opacity: 0,
  }, {
    y: 0, rotateX: 0, opacity: 1,
    duration: M.dur(660), ease: M.eases.back, stagger: M.dur(56),
  }, M.dur(-180));
  tl.to('.hero-sub', { opacity: 1, y: -14, duration: M.dur(520) }, '-=0.1');
  tl.to('.hero-meta > span', { opacity: 1, y: -10, duration: M.dur(460), stagger: M.dur(90) }, '-=0.05');
  tl.to(actions, { opacity: 1, y: -10, duration: M.dur(480), stagger: M.dur(70) }, '-=0.12');

  // 按钮出现后约 1s 淡出 —— 只做视觉氛围，点击入口仍走导航栏 / 下载区
  tl.to(actions, { opacity: 0, y: 10, duration: M.dur(620), pointerEvents: 'none' }, '+1.1');
})();