/* cards —— 下载卡片：滚入交错浮现 + 悬停时的顶线扫光。
 * 悬停位移 ≤2px、时长短、只动 transform/border/背景，符合微交互约束。 */
(function () {
  var M = window.PACC_MOTION;
  if (!M) return;

  var cards = document.querySelectorAll('.card');
  if (!cards.length) return;

  // 就地覆盖个别 hover keyframe：保留 CSS 里更克制的位移
  cards.forEach(function (card) {
    card.dataset.motion = 'true';
  });

  if (M.reduced || M.tiny) {
    cards.forEach(function (c) { gsap.set(c, { opacity: 1 }); });
    return;
  }

  // 入场：只有在下滚动时才浮现，避免首屏双击;错峰用自然阻尼
  gsap.from(cards, {
    y: 26,
    opacity: 0,
    duration: M.dur(560),
    ease: M.eases.inSoft,
    stagger: M.dur(70),
    scrollTrigger: {
      trigger: '.grid',
      start: 'top 86%',
      once: true,
    },
  });

  // 悬停顶线扫光复刻 CSS::before，JS 管位移部分（≤2px）
  cards.forEach(function (card, i) {
    card.addEventListener('mouseenter', function () {
      if (M.reduced) return;
      gsap.to(card, { y: -2, duration: M.dur(180), ease: 'power2.out', overwrite: 'auto' });
    });
    card.addEventListener('mouseleave', function () {
      gsap.to(card, { y: 0, duration: M.dur(180), ease: 'power2.out', overwrite: 'auto' });
    });
  });
})();