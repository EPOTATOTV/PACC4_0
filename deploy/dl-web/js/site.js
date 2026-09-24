/* PACC download —— 站点脚本：主题切换 + 发布信息（校验和 / 文件大小）。
 *
 * 与动效无关，独立于 motion.js：动效整体降级时这两件事仍须可用。
 * 发布信息全部来自 /api/dl/latest；取不到就显示占位文案，不白屏、不报错到控制台。
 */
(function () {
  'use strict';

  var THEME_KEY = 'pacc-dl-theme';

  /* ============================================================
     1) 主题切换 —— 深色 / 浅色，默认跟随系统
     data-theme 的首帧值由 <head> 里的内联脚本设定，这里只负责切换与持久化。
     ============================================================ */
  (function () {
    var root = document.documentElement;
    var btn = document.getElementById('theme-toggle');
    var mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;

    function stored() {
      try {
        var v = window.localStorage.getItem(THEME_KEY);
        return v === 'light' || v === 'dark' ? v : null;
      } catch (e) {
        return null;
      }
    }

    function label(theme) {
      var t = window.PACC_LANG ? window.PACC_LANG.t(theme === 'light' ? 'ui.theme.toDark' : 'ui.theme.toLight') : null;
      if (btn && t) btn.setAttribute('aria-label', t);
      if (btn) btn.setAttribute('title', t || '');
    }

    function set(theme, persist) {
      root.dataset.theme = theme;
      if (persist) {
        try {
          window.localStorage.setItem(THEME_KEY, theme);
        } catch (e) { /* 隐私模式：本次生效即可 */ }
      }
      label(theme);
    }

    if (!root.dataset.theme) set(stored() || (mq && mq.matches ? 'light' : 'dark'), false);
    else label(root.dataset.theme);

    if (btn) {
      btn.addEventListener('click', function () {
        set(root.dataset.theme === 'light' ? 'dark' : 'light', true);
      });
    }

    // 用户没手动选过时，跟随系统切换
    if (mq) {
      var onSys = function (e) {
        if (!stored()) set(e.matches ? 'light' : 'dark', false);
      };
      if (typeof mq.addEventListener === 'function') mq.addEventListener('change', onSys);
      else if (typeof mq.addListener === 'function') mq.addListener(onSys);
    }

    // 语言切换后 aria-label / title 要跟着变
    document.addEventListener('pacc:lang', function () { label(root.dataset.theme); });
  })();

  /* ============================================================
     2) 发布信息 —— 校验和区块与卡片上的文件大小
     ============================================================ */
  var PLATFORM_NAME_KEY = {
    WIN: 'card.win.title',
    LNX: 'card.lnx.title',
    APK: 'card.apk.title',
    IOS: 'card.ios.title',
    HMY: 'card.hmy.title',
    JVM: 'check.plat.jvm',
  };
  var ZERO_SHA = /^0+$/;

  var releases = null; // null=未取到；[]=取到但为空

  function t(key) {
    return window.PACC_LANG ? window.PACC_LANG.t(key) : null;
  }

  function platformName(code) {
    var key = PLATFORM_NAME_KEY[code];
    return (key && t(key)) || code;
  }

  /** 字节转可读单位；0 或非法值返回空串（调用方据此隐藏）。 */
  function formatSize(bytes) {
    var n = Number(bytes);
    if (!isFinite(n) || n <= 0) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(1) + ' MB';
  }

  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text);
    }
    // 非安全上下文（老浏览器 / http 内网）回退到临时选区
    return new Promise(function (resolve, reject) {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.top = '-1000px';
      document.body.appendChild(ta);
      ta.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
      document.body.removeChild(ta);
      ok ? resolve() : reject(new Error('copy failed'));
    });
  }

  function renderChecksums() {
    var box = document.getElementById('check-box');
    if (!box) return;
    box.textContent = '';

    if (releases === null) {
      var p = document.createElement('p');
      p.className = 'check-hint';
      p.setAttribute('data-i18n', 'check.failed');
      p.textContent = t('check.failed') || '';
      box.appendChild(p);
      return;
    }
    if (!releases.length) {
      var e = document.createElement('p');
      e.className = 'check-hint';
      e.textContent = t('check.failed') || '';
      box.appendChild(e);
      return;
    }

    releases.forEach(function (rel) {
      var pending = !rel.sha256 || ZERO_SHA.test(rel.sha256);
      var name = platformName(rel.platform);
      if (rel.artifact && rel.artifact !== 'client') name += ' · ' + rel.artifact;

      var row = document.createElement('div');
      row.className = 'check';

      var head = document.createElement('div');
      head.className = 'check-head';

      var nameEl = document.createElement('span');
      nameEl.className = 'check-name mono';
      nameEl.textContent = name + ' · ' + rel.version;
      head.appendChild(nameEl);

      var size = formatSize(rel.sizeBytes);
      if (size) {
        var sizeEl = document.createElement('span');
        sizeEl.className = 'check-size mono';
        sizeEl.textContent = (t('dl.size') || '') + ' ' + size;
        head.appendChild(sizeEl);
      }
      row.appendChild(head);

      if (pending) {
        var miss = document.createElement('span');
        miss.className = 'check-pending';
        miss.textContent = t('check.pending') || '';
        row.appendChild(miss);
      } else {
        var line = document.createElement('div');
        line.className = 'check-line';

        var code = document.createElement('code');
        code.className = 'mono';
        code.textContent = rel.sha256;
        line.appendChild(code);

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'copy-btn';
        btn.textContent = t('check.copy') || 'Copy';
        var ariaTpl = t('check.aria') || 'Copy SHA-256 for %s';
        btn.setAttribute('aria-label', ariaTpl.indexOf('%s') > -1 ? ariaTpl.replace('%s', name) : ariaTpl + ' ' + name);
        btn.addEventListener('click', function () {
          var done = function () {
            btn.textContent = t('check.copied') || 'Copied';
            btn.classList.add('copied');
            setTimeout(function () {
              btn.textContent = t('check.copy') || 'Copy';
              btn.classList.remove('copied');
            }, 1600);
          };
          copyText(rel.sha256).then(done, function () {
            btn.textContent = t('check.copyFail') || 'Copy failed';
          });
        });
        line.appendChild(btn);
        row.appendChild(line);
      }

      box.appendChild(row);
    });
  }

  function renderSizes() {
    document.querySelectorAll('.card[data-plat]').forEach(function (card) {
      var holder = card.querySelector('.card-size');
      if (!holder) return;
      holder.textContent = '';
      if (!releases) return;
      var plat = card.dataset.plat;
      var rel = null;
      for (var i = 0; i < releases.length; i += 1) {
        if (releases[i].platform === plat && releases[i].artifact === 'client') { rel = releases[i]; break; }
      }
      if (!rel) return;
      var size = formatSize(rel.sizeBytes);
      if (!size) return; // 大小为 0 / 未知时不显示
      holder.textContent = size;
      holder.hidden = false;
    });
  }

  function renderAll() {
    renderChecksums();
    renderSizes();
  }

  function loadReleases() {
    var ctl = typeof AbortController === 'function' ? new AbortController() : null;
    var timer = ctl ? setTimeout(function () { ctl.abort(); }, 8000) : null;

    fetch('/api/dl/latest', {
      credentials: 'omit',
      headers: { Accept: 'application/json' },
      signal: ctl ? ctl.signal : undefined,
    })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        var ct = r.headers.get('content-type') || '';
        // 网关未代理时可能落到 index.html，这里挡掉非 JSON 响应
        if (ct.indexOf('json') === -1) throw new Error('not json');
        return r.json();
      })
      .then(function (list) {
        releases = Array.isArray(list) ? list : [];
        renderAll();
      })
      .catch(function () {
        releases = null;
        renderAll();
      })
      .then(function () {
        if (timer) clearTimeout(timer);
      });
  }

  function boot() {
    var box = document.getElementById('check-box');
    if (box) {
      var p = document.createElement('p');
      p.className = 'check-hint';
      p.textContent = t('check.loading') || '';
      box.appendChild(p);
    }
    loadReleases();
    document.addEventListener('pacc:lang', renderAll);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();