/* PACC download —— 动效总装（v5.1 合并版）
 *
 * 原 config.js / background.js / counter.js / cards.js / scroll.js / hero.js /
 * cursor-glow.js / main.js 八个模块合并为本文件，减少首屏请求数。
 * 各段仍以 IIFE 隔离，保持原来的「提前 return 退出本段」语义。
 * 顺序即执行顺序：配置 → 平台识别 → 首屏入场 → 纵深/视差 → 计数 → 卡片 → 锚点 → 指针光斑 → 兜底。
 * 依赖全局 gsap / ScrollTrigger / ScrollToPlugin（均在 <script defer> 中先于本文件加载）。
 */
(function () {
  'use strict';

  /* ============================================================
     1) 动效配置 —— 统一开关、降级与时长基准
     尊重系统减弱动态；移动端抽稀粒子、关闭视差。低端环境直接关掉 JS 动效。
     ============================================================ */
  var M = (function () {
    var mq = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
    var reduced = !!(mq && mq.matches);
    var tiny = navigator.hardwareConcurrency && navigator.hardwareConcurrency <= 4;

    function isMobile() {
      return window.innerWidth < 720 || (window.matchMedia && window.matchMedia('(pointer: coarse)').matches);
    }

    /* 时间基准：标准时长为基准毫秒；减弱动态时额外压到极短，视觉上几乎为零 */
    function dur(ms) {
      if (reduced) return Math.min(ms * 0.12, 100);
      return ms / 1000;
    }

    if (typeof gsap !== 'undefined') gsap.registerPlugin(ScrollTrigger, ScrollToPlugin);

    var api = {
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
    window.PACC_MOTION = api;
    return api;
  })();

  // GSAP 缺席（文件 404 / JS 被禁）时保留 <html class="js-fallback">，
  // 由 styles.css 的关键帧承担入场；就绪则交给 GSAP，两套动画不叠加。
  if (typeof gsap === 'undefined') return;
  document.documentElement.classList.remove('js-fallback');

  /* ============================================================
     2) 平台识别 —— 自动推荐对应平台的下载包
     只读 UA，不做指纹；未知平台不推荐，避免误导。
     ============================================================ */
  var detectPlatform = function () {
    var ua = (navigator.userAgent || '').toLowerCase();
    if (ua.indexOf('harmony') > -1 || ua.indexOf('openharmony') > -1) return 'harmony';
    if (ua.indexOf('android') > -1) return 'android';
    if (ua.indexOf('iphone') > -1 || ua.indexOf('ipad') > -1 || ua.indexOf('ipod') > -1) return 'ios';
    if (ua.indexOf('windows nt') > -1) return 'win';
    if (ua.indexOf('mac os x') > -1 || ua.indexOf('macintosh') > -1) return 'mac';
    if (ua.indexOf('linux') > -1 || ua.indexOf('x11') > -1 || ua.indexOf('cros') > -1) return 'linux';
    return 'unknown';
  };
  M.detectPlatform = detectPlatform;

  /* ============================================================
     3) 首屏入场 —— 眉标 → 标题逐字翻起 → 副标题 → meta → 行动按钮
     ============================================================ */
  (function () {
    var hero = document.querySelector('.hero');
    if (!hero) return;

    var title = hero.querySelector('.hero-title');
    if (title && !title.dataset.split) {
      title.dataset.split = 'true';
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
    if (!allHeroEls.length) return;

    if (M.reduced || M.tiny) {
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

  /* ============================================================
     4) 下载卡片推荐位 —— 按 UA 给命中卡片加 .recommended 与「推荐」标签
     桌面 Windows 访问时同时高亮 Linux；移动端进入页面后滚动到下载区。
     ============================================================ */
  (function () {
    var plat = detectPlatform();
    var cards = document.querySelectorAll('.card[data-plat]');
    if (!cards.length || plat === 'unknown') return;

    var hit = plat === 'win' ? ['win', 'linux'] : [plat];
    var firstHit = null;

    cards.forEach(function (card) {
      if (hit.indexOf(card.dataset.plat) === -1) return;
      card.classList.add('recommended');
      if (!card.querySelector('.rec-tag')) {
        var tag = document.createElement('span');
        tag.className = 'rec-tag';
        tag.setAttribute('data-i18n', 'dl.recommended');
        tag.textContent = '推荐';
        card.appendChild(tag);
      }
      if (!firstHit) firstHit = card;
    });

    if (!firstHit) return;

    // 移动端：落地后平滑滚到推荐卡片，省去用户自己找
    if (!M.isMobile() || M.reduced) return;
    window.addEventListener('load', function () {
      var y = firstHit.getBoundingClientRect().top + window.scrollY - 76;
      if (y < 120) return;
      try {
        gsap.to(window, { scrollTo: { y: y }, duration: M.dur(760), ease: 'power3.inOut' });
      } catch (e) { /* ScrollToPlugin 缺失时保持原位 */ }
    });
  })();

  /* ============================================================
     5) 纵深与视差 —— 背景层 0.5x / 中间层 0.8x / 前景层正常速度
     移动端与减弱动态下整体不启用，避免掉帧与晕动。
     ============================================================ */
  (function () {
    if (M.reduced || M.tiny || M.isMobile()) return;

    var hero = document.querySelector('.hero');
    if (!hero) return;

    var layers = [
      { sel: '.hero-bg', yPercent: 50 },       // 最远：慢
      { sel: '.hero-img-wrap', yPercent: 20 }, // 中景
    ];

    layers.forEach(function (l) {
      var el = hero.querySelector(l.sel);
      if (!el) return;
      gsap.to(el, {
        yPercent: l.yPercent,
        ease: 'none',
        scrollTrigger: {
          trigger: hero,
          start: 'top top',
          end: 'bottom top',
          scrub: true,
        },
      });
    });
  })();

  /* ============================================================
     6) 背景粒子 —— 缓慢上升的细小光点，抽稀至移动端一半，随机相位避免同速进场
     ============================================================ */
  (function () {
    if (M.reduced || M.tiny) return;
    if (!document.querySelector('.bg')) return;

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

      gsap.to(d, {
        y: -(60 + Math.random() * 160),
        x: (Math.random() - 0.5) * 120,
        opacity: 0,
        duration: 22 + Math.random() * 18,
        delay: -Math.random() * 24,
        repeat: -1,
        ease: 'sine.inOut',
      });
    }
  })();

  /* ============================================================
     7) 数字滚动 —— .count[data-count] 从 0 滚到目标值
     ============================================================ */
  (function () {
    document.querySelectorAll('.count[data-count]').forEach(function (el) {
      var target = parseFloat(el.getAttribute('data-count'));
      if (isNaN(target)) return;
      var fmt = el.getAttribute('data-fmt');
      var decimals = fmt ? parseInt(fmt, 10) : 0;

      if (M.reduced) {
        el.textContent = target.toFixed(decimals);
        return;
      }

      var obj = { v: 0 };
      gsap.to(obj, {
        v: target,
        duration: M.dur(900),
        ease: 'power2.out',
        onUpdate: function () { el.textContent = obj.v.toFixed(decimals); },
      });
    });
  })();

  /* ============================================================
     8) 下载卡片 —— 滚入交错浮现 + 悬停时 ≤2px 顶线扫光
     ============================================================ */
  (function () {
    var cards = document.querySelectorAll('.card');
    if (!cards.length) return;

    cards.forEach(function (card) { card.dataset.motion = 'true'; });

    if (M.reduced || M.tiny) {
      cards.forEach(function (c) { gsap.set(c, { opacity: 1 }); });
      return;
    }

    gsap.from(cards, {
      y: 46,
      opacity: 0,
      duration: M.dur(680),
      ease: M.eases.expo,
      stagger: M.dur(90),
      scrollTrigger: {
        trigger: '.grid',
        start: 'top 88%',
        once: true,
      },
    });

    cards.forEach(function (card) {
      card.addEventListener('mouseenter', function () {
        if (M.reduced) return;
        gsap.to(card, { y: -2, duration: M.dur(180), ease: 'power2.out', overwrite: 'auto' });
      });
      card.addEventListener('mouseleave', function () {
        gsap.to(card, { y: 0, duration: M.dur(180), ease: 'power2.out', overwrite: 'auto' });
      });
    });
  })();

  /* ============================================================
     9) 锚点平滑滚动 + 章节滚入 + 导航收束态
     ============================================================ */
  (function () {
    document.addEventListener('click', function (e) {
      var a = e.target.closest('a[href^="#"]');
      if (!a) return;
      var id = a.getAttribute('href');
      if (!id || id === '#') return;
      var target = document.querySelector(id);
      if (!target) return;
      if (M.reduced) return; // 直接原生跳转
      e.preventDefault();
      var y = Math.max(0, target.getBoundingClientRect().top + window.scrollY - 64);
      gsap.to(window, { scrollTo: { y: y }, duration: M.dur(620), ease: 'power3.inOut', overwrite: 'auto' });
    });

    var revealEls = document.querySelectorAll('.reveal');
    if (M.reduced || M.tiny) {
      revealEls.forEach(function (el) { gsap.set(el, { opacity: 1 }); });
    } else {
      revealEls.forEach(function (el) {
        gsap.from(el, {
          y: 52,
          opacity: 0,
          duration: M.dur(680),
          ease: M.eases.expo,
          scrollTrigger: { trigger: el, start: 'top 90%', once: true },
        });
      });
    }

    var nav = document.querySelector('.nav');
    if (nav) {
      ScrollTrigger.create({
        start: 'top -60',
        onToggle: function (self) { nav.classList.toggle('scrolled', self.isActive); },
      });
    }
  })();

  /* ============================================================
     10) 指针光斑 —— 桌面端跟随鼠标的微弱暖光，纯装饰
     ============================================================ */
  (function () {
    if (M.reduced || M.tiny) return;
    if (window.matchMedia && !window.matchMedia('(pointer: fine)').matches) return;

    var glow = document.createElement('div');
    glow.setAttribute('aria-hidden', 'true');
    glow.className = 'pacc-cursor-glow';
    document.body.appendChild(glow);

    var x = window.innerWidth / 2;
    var y = -160; // 初始在视口上方，避免首屏正中突兀出现
    gsap.set(glow, { x: x, y: y, opacity: 0 });

    var ghost = gsap.to(glow, { x: x + 40, y: y + 40, duration: M.dur(5200), ease: 'none', repeat: -1, yoyo: true, paused: true });
    gsap.to(glow, { opacity: 0.55, duration: M.dur(2400), ease: 'sine.inOut' });

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

  /* ============================================================
     11) 收尾 —— ScrollTrigger 刷新兜底 + 降级终态 + 下载计数信标
     ============================================================ */
  (function () {
    window.addEventListener('load', function () {
      if (typeof ScrollTrigger !== 'undefined') ScrollTrigger.refresh();
    });

    if (M.reduced || M.tiny) {
      document.querySelectorAll('.card, .reveal, .hero-eyebrow, .hero-sub, .hero-meta')
        .forEach(function (el) { el.style.opacity = '1'; });
    }

    // 命中下载链接时静默上报（同源 /api/dl/track，best-effort）
    var platformMap = { win: 'WIN', lnx: 'LNX', apk: 'APK', ios: 'IOS', hmy: 'HMY', probe: 'JVM' };
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
      } catch (x) { /* 静默：计数失败不能影响下载 */ }
    });

    function guessPlatform(file) {
      if (/windows/i.test(file)) return 'WIN';
      if (/linux/i.test(file)) return 'LNX';
      if (/android|\.apk/i.test(file)) return 'APK';
      if (/ios|ipad|iphone/i.test(file)) return 'IOS';
      if (/harmony/i.test(file)) return 'HMY';
      return 'UNK';
    }
  })();
})();