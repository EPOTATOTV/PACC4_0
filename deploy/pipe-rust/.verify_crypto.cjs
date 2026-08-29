// 校验 deploy/pipe-rust 中 SHA-256 / HMAC-SHA256 / AES-256-CBC 的算法逻辑
// 镜像 Rust 实现并与 Node 内置 crypto 比对（Node >= 12 可用）
const crypto = require('crypto');

// ---------- 镜像 Rust 实现 ----------
function gfMul(a, b) {
  let p = 0;
  for (let i = 0; i < 8; i++) {
    if (b & 1) p ^= a;
    const hi = a & 0x80;
    a = (a << 1) & 0xff;
    if (hi) a ^= 0x1b;
    b >>= 1;
  }
  return p;
}

function gfInv(a) {
  if (a === 0) return 0;
  let r = a;
  for (let i = 1; i < 254; i++) r = gfMul(r, a);
  return r;
}

function rotr8(x, n) { return ((x >>> n) | (x << (8 - n))) & 0xff; }
function rotl8(x, n) { return rotr8(x, 8 - n); }

const SBOX = (() => {
  const s = [];
  for (let i = 0; i < 256; i++) {
    const x = gfInv(i);
    s.push((x ^ rotl8(x, 1) ^ rotl8(x, 2) ^ rotl8(x, 3) ^ rotl8(x, 4) ^ 0x63) & 0xff);
  }
  return s;
})();

function rcon(i) {
  let v = 1;
  for (let k = 0; k < i; k++) {
    const hi = v & 0x80;
    v = (v << 1) & 0xff;
    if (hi) v ^= 0x1b;
  }
  return v;
}

function expandKey(key) {
  const w = new Array(60).fill(0);
  for (let i = 0; i < 8; i++) w[i] = key.readUInt32BE(i * 4);
  for (let i = 8; i < 60; i++) {
    let temp = w[i - 1];
    if (i % 8 === 0) {
      const r = ((temp & 0xffffff) << 8) | (temp >>> 24);
      const b = [(r >>> 24) & 0xff, (r >>> 16) & 0xff, (r >>> 8) & 0xff, r & 0xff];
      temp = (((SBOX[b[0]] << 24) | (SBOX[b[1]] << 16) | (SBOX[b[2]] << 8) | SBOX[b[3]]) >>> 0) ^ (rcon(i / 8 - 1) << 24);
    } else if (i % 8 === 4) {
      const b = [(temp >>> 24) & 0xff, (temp >>> 16) & 0xff, (temp >>> 8) & 0xff, temp & 0xff];
      temp = ((SBOX[b[0]] << 24) | (SBOX[b[1]] << 16) | (SBOX[b[2]] << 8) | SBOX[b[3]]) >>> 0;
    }
    w[i] = (w[i - 8] ^ temp) >>> 0;
  }
  const rk = [];
  for (let r = 0; r < 15; r++) {
    const buf = Buffer.alloc(16);
    for (let i = 0; i < 4; i++) buf.writeUInt32BE(w[4 * r + i], i * 4);
    rk.push(buf);
  }
  return rk;
}

function encryptBlock(rk, block) {
  let st = Buffer.alloc(16);
  for (let i = 0; i < 16; i++) st[i] = block[i] ^ rk[0][i];
  for (let r = 1; r < 14; r++) {
    st = subBytes(st);
    st = shiftRows(st);
    st = mixColumns(st);
    st = xor(st, rk[r]);
  }
  st = subBytes(st);
  st = shiftRows(st);
  st = xor(st, rk[14]);
  return st;
}

function subBytes(st) {
  const o = Buffer.alloc(16);
  for (let i = 0; i < 16; i++) o[i] = SBOX[st[i]];
  return o;
}

function shiftRows(st) {
  // 列主序：st[col*4+row]
  const m = [];
  for (let c = 0; c < 4; c++) m.push([st[c * 4], st[c * 4 + 1], st[c * 4 + 2], st[c * 4 + 3]]);
  for (let row = 1; row < 4; row++) {
    for (let k = 0; k < row; k++) {
      const t0 = m[0][row], t1 = m[1][row], t2 = m[2][row], t3 = m[3][row];
      m[0][row] = t1; m[1][row] = t2; m[2][row] = t3; m[3][row] = t0;
    }
  }
  const o = Buffer.alloc(16);
  for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) o[c * 4 + r] = m[c][r];
  return o;
}

function mixColumns(st) {
  const o = Buffer.from(st);
  for (let c = 0; c < 4; c++) {
    const a = st[c * 4], b = st[c * 4 + 1], cc = st[c * 4 + 2], d = st[c * 4 + 3];
    o[c * 4] = gfMul(a, 2) ^ gfMul(b, 3) ^ cc ^ d;
    o[c * 4 + 1] = a ^ gfMul(b, 2) ^ gfMul(cc, 3) ^ d;
    o[c * 4 + 2] = a ^ b ^ gfMul(cc, 2) ^ gfMul(d, 3);
    o[c * 4 + 3] = gfMul(a, 3) ^ b ^ cc ^ gfMul(d, 2);
  }
  return o;
}

function xor(a, b) {
  const o = Buffer.alloc(a.length);
  for (let i = 0; i < a.length; i++) o[i] = a[i] ^ b[i];
  return o;
}

function pkcs7Pad(d) {
  const pad = 16 - (d.length % 16);
  const o = Buffer.alloc(d.length + pad);
  d.copy(o);
  for (let i = 0; i < pad; i++) o[d.length + i] = pad;
  return o;
}

function cbcEncrypt(rk, iv, data) {
  const padded = pkcs7Pad(data);
  const out = Buffer.alloc(padded.length);
  let prev = iv;
  for (let i = 0; i < padded.length; i += 16) {
    const blk = xor(padded.slice(i, i + 16), prev);
    const ct = encryptBlock(rk, blk);
    ct.copy(out, i);
    prev = ct;
  }
  return out;
}

// ---------- 校验 ----------
let ok = true;
function check(name, got, want) {
  const pass = got === want;
  console.log(`[${pass ? 'OK  ' : 'FAIL'}] ${name}`);
  ok = ok && pass;
}

// SHA-256
check('SHA-256(abc)',
  crypto.createHash('sha256').update('abc').digest('hex'),
  'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
check('SHA-256(empty)',
  crypto.createHash('sha256').update('').digest('hex'),
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');

// HMAC-SHA256 (RFC 4231 TC1)
const key = Buffer.alloc(20, 0x0b);
check('HMAC-SHA256(TC1)',
  crypto.createHmac('sha256', key).update('Hi There').digest('hex'),
  'b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7');

// AES-256 NIST KAT：key=00x32, plaintext=00x16
const rk = expandKey(Buffer.alloc(32));
const ct = encryptBlock(rk, Buffer.alloc(16));
check('AES-256(KAT00)',
  ct.toString('hex'),
  'dc95c078a2408989ad48a21492842087');

// AES-256-CBC roundtrip 与 Node 内置 crypto 对比
const iv = Buffer.alloc(16);
const data = Buffer.from('pacc detection event payload');
const mine = cbcEncrypt(rk, iv, data);
const nodeCipher = crypto.createCipheriv('aes-256-cbc', Buffer.alloc(32), iv);
const nodeCt = Buffer.concat([nodeCipher.update(data), nodeCipher.final()]);
check('AES-256-CBC vs Node crypto',
  mine.toString('hex'),
  nodeCt.toString('hex'));

console.log(ok ? '\nALL PASS' : '\nSOME FAILED');
process.exit(ok ? 0 : 1);
