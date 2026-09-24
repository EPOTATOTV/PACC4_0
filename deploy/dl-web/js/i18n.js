/* PACC download —— 多语言：语言判定、文案注入、切换控件。
 *
 * HTML 里保留中文原文作为「无 JS / 搜索引擎」的兜底，脚本按所选语言覆盖。
 * 语言优先级：URL ?lang= → localStorage → navigator.language → 中文。
 * 用户主动切换时写回 localStorage 并把 ?lang= 同步进地址栏（方便分享带语言的链接）；
 * 首次落地只注入文案，不写存储、不改地址栏。
 */
(function () {
  var LANGS = ['zh-CN', 'en'];
  var LABEL = { 'zh-CN': '中', en: 'EN' };
  var NAME = { 'zh-CN': '中文', en: 'English' };
  var STORE_KEY = 'pacc-dl-lang';
  var DICT = window.PACC_I18N || {};

  function fromQuery() {
    var m = /[?&]lang=([\w-]+)/.exec(window.location.search);
    if (!m) return null;
    var v = m[1];
    if (LANGS.indexOf(v) > -1) return v;
    if (/^zh/i.test(v)) return 'zh-CN'; // zh / zh-Hans / zh-TW 统一走简体表
    if (/^en/i.test(v)) return 'en';
    return null;
  }

  function fromStore() {
    try {
      var v = window.localStorage.getItem(STORE_KEY);
      return LANGS.indexOf(v) > -1 ? v : null;
    } catch (e) {
      return null; // 隐私模式下 localStorage 可能不可用
    }
  }

  function fromBrowser() {
    return /^zh/i.test(navigator.language || '') ? 'zh-CN' : 'en';
  }

  function dict(lang) {
    return DICT[lang] || DICT['zh-CN'] || {};
  }

  /** 取文案：目标语言缺失时回退中文，中文也没有则返回 null（保留 HTML 原文）。 */
  function t(lang, key) {
    var v = dict(lang)[key];
    if (v !== undefined) return v;
    var fb = dict('zh-CN')[key];
    return fb !== undefined ? fb : null;
  }

  var current = fromQuery() || fromStore() || fromBrowser();
  if (LANGS.indexOf(current) === -1) current = 'zh-CN';

  /**
   * 注入文案。
   * @param {string} lang 目标语言
   * @param {boolean} persist 是否持久化（仅用户主动切换时为 true）
   */
  function apply(lang, persist) {
    current = LANGS.indexOf(lang) > -1 ? lang : 'zh-CN';
    document.documentElement.lang = current;

    document.querySelectorAll('[data-i18n]').forEach(function (el) {
      var v = t(current, el.getAttribute('data-i18n'));
      if (v !== null) el.textContent = v;
    });
    document.querySelectorAll('[data-i18n-html]').forEach(function (el) {
      var v = t(current, el.getAttribute('data-i18n-html'));
      if (v !== null) el.innerHTML = v;
    });
    document.querySelectorAll('[data-i18n-attr]').forEach(function (el) {
      // 形如 "aria-label=check.aria,title=dl.title"
      el.getAttribute('data-i18n-attr').split(',').forEach(function (pair) {
        var kv = pair.split('=');
        if (kv.length !== 2) return;
        var v = t(current, kv[1].trim());
        if (v !== null) el.setAttribute(kv[0].trim(), v);
      });
    });

    var title = t(current, 'html.title');
    if (title) document.title = title;
    var desc = document.querySelector('meta[name="description"]');
    if (desc) {
      var d = t(current, 'html.desc');
      if (d) desc.setAttribute('content', d);
    }

    var cur = document.getElementById('lang-current');
    if (cur) cur.textContent = LABEL[current] || current;
    document.querySelectorAll('.lang-menu [data-lang]').forEach(function (li) {
      li.setAttribute('aria-selected', String(li.getAttribute('data-lang') === current));
    });

    if (!persist) return;
    try {
      window.localStorage.setItem(STORE_KEY, current);
    } catch (e) { /* 忽略：写不进去不影响本次切换 */ }
    if (window.history && window.history.replaceState) {
      var q = current === 'zh-CN' ? '' : '?lang=' + current;
      window.history.replaceState(null, '', window.location.pathname + q + window.location.hash);
    }
  }

  function wireSwitcher() {
    var btn = document.getElementById('lang-toggle');
    var menu = document.getElementById('lang-menu');
    if (!btn || !menu) return;

    function close() {
      menu.hidden = true;
      btn.setAttribute('aria-expanded', 'false');
    }

    // 菜单项的语言全名在这里补，HTML 只写 data-lang
    menu.querySelectorAll('[data-lang]').forEach(function (li) {
      var l = li.getAttribute('data-lang');
      if (NAME[l]) li.textContent = NAME[l];
    });

    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      menu.hidden = !menu.hidden;
      btn.setAttribute('aria-expanded', String(!menu.hidden));
    });

    menu.addEventListener('click', function (e) {
      var li = e.target.closest('[data-lang]');
      if (!li) return;
      apply(li.getAttribute('data-lang'), true);
      close();
      // 通知其它脚本（校验和等动态文案）跟着刷新
      document.dispatchEvent(new CustomEvent('pacc:lang', { detail: { lang: current } }));
    });

    document.addEventListener('click', close);
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') close();
    });
  }

  window.PACC_LANG = {
    current: function () { return current; },
    supported: LANGS.slice(),
    t: function (key) { return t(current, key); },
    apply: apply,
  };

  apply(current, false);
  wireSwitcher();
})();