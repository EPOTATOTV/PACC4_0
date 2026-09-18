/* hero —— 首屏入场序列：眉标 → 标题逐字翻起 → 副标题 → 平台/版本 meta → 行动按钮。
 * 各元素错峰 + 自然阻尼;减弱动态时直接呈现终态，不做任何移动。 */
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

  if (M.reduced || M.tiny) {
    gsap.set(hero.querySelectorAll('.hero-eyebrow, .pacc-hero-char, .hero-sub, .hero-meta, .hero-actions'), { opacity: 1 });
    return;
  }

  gsap.set(hero.querySelectorAll('.hero-eyebrow, .pacc-hero-char, .hero-sub, .hero-meta, .hero-actions'), { opacity: 0 });

  var tl = gsap.timeline({ delay: M.dur(120), defaults: { ease: M.eases.inSoft } });

  tl.to('.hero-eyebrow', { opacity: 1, y: -6, duration: M.dur(420) }, 0);
  tl.fromTo(title.querySelectorAll('.pacc-hero-char'), {
    y: -60, rotateX: -90, opacity: 0,
  }, {
    y: 0, rotateX: 0, opacity: 1,
    duration: M.dur(620), ease: M.eases.back, stagger: M.dur(52),
  }, M.dur(-180));
  tl.to('.hero-sub', { opacity: 1, y: -8, duration: M.dur(480) }, '-=0.1');
  tl.to('.hero-meta > span', { opacity: 1, y: -6, duration: M.dur(420), stagger: M.dur(90) }, '-=0.05');
  tl.to('.hero-actions > *', { opacity: 1, y: -6, duration: M.dur(440), stagger: M.dur(70) }, '-=0.12');
})();