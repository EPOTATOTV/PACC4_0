/**
 * PACC 开放 API（/api/v1/**）开发者客户端 SDK 示例（Node.js，内置 crypto）。
 *
 * 鉴权头（每个请求均需携带）：
 *   X-PTV-Key       = keyId
 *   X-PTV-Timestamp = 当前毫秒时间戳（与服务器差值需 < 300s）
 *   X-PTV-Nonce     = 一次性随机串（防重放）
 *   X-PTV-Signature = HMAC-SHA256(secret, canonical)
 *   其中 canonical = `${METHOD}\n${PATH}\n${TIMESTAMP}\n${BODY_SHA256_HEX}`
 */
const crypto = require('crypto');

function sign(method, path, timestampSecondsMs, body) {
  const bodySha = crypto.createHash('sha256').update(body, 'utf8').digest('hex');
  const canonical = `${method}\n${path}\n${timestampSecondsMs}\n${bodySha}`;
  return crypto.createHmac('sha256', process.env.PACC_API_SECRET).update(canonical, 'utf8').digest('hex');
}

async function call(baseUrl, path, { method = 'GET', body = '' } = {}) {
  const timestampMs = String(Date.now());
  const nonce = crypto.randomUUID();
  const signature = sign(method, path, timestampMs, body);

  const res = await fetch(baseUrl + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-PTV-Key': process.env.PACC_API_KEY,
      'X-PTV-Timestamp': timestampMs,
      'X-PTV-Nonce': nonce,
      'X-PTV-Signature': signature,
    },
    body: method === 'GET' ? undefined : body,
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  return res.json();
}

// 示例：拉取玩家信誉
(async () => {
  const data = await call('https://api.potatotv.asia', '/api/v1/players/PTxxxxxxxxxxxx/reputation');
  console.log(data);
})();