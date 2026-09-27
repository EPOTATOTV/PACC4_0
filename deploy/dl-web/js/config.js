/* PACC 下载站运行时配置
 *
 * 版本号唯一来源。scripts/bump-version.sh 会同步这里的 PACC_VERSION，
 * 页面版本徽标与结构化数据（JSON-LD）都从它取值，
 * 免得 HTML、i18n 文案、JS 三处版本号各自漂移。
 */
window.PACC_CONFIG = (function () {
  var PACC_VERSION = '5.4.0';

  return {
    PACC_VERSION: PACC_VERSION,
    // 发布包目录，与 files/version.json 里的 URL 同源
    filesBase: '/files',
    // 下载站自身对外品牌版本，页脚等处引用
    releaseLabel: 'DF Alpha 1.0.0'
  };
})();