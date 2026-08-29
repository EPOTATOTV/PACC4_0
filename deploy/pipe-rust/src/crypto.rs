//! 本地加密持久化引擎：AES-256-CBC（PKCS#7）+ HMAC-SHA256（Encrypt-then-MAC）。
//!
//! 纯标准库实现，用于检测事件本地落盘的机密性与完整性保护。
//! 密钥来源：环境变量 PIPE_KEY（32 字节 hex）；未配置时回退为 HMAC 密钥派生。
//! 安全说明：生产环境应使用 KMS / Vault 托管密钥，此处提供自包含参考实现。

use crate::sha256::{hmac_sha256, sha256};

// ---------- AES-256 核心 ----------

/// 依据 FIPS 197 §5.1.1 计算 S-box（GF(2^8) 逆元 + 仿射变换）。
fn sbox_table() -> [u8; 256] {
    let mut s = [0u8; 256];
    for i in 0u16..256 {
        s[i as usize] = affine(gf_inv(i as u8));
    }
    s
}

/// GF(2^8) 乘法（多项式 0x11b）。
fn gf_mul(a: u8, b: u8) -> u8 {
    let mut a = a;
    let mut b = b;
    let mut p = 0u8;
    for _ in 0..8 {
        if b & 1 != 0 {
            p ^= a;
        }
        let hi = a & 0x80;
        a <<= 1;
        if hi != 0 {
            a ^= 0x1b;
        }
        b >>= 1;
    }
    p
}

/// GF(2^8) 逆元（0 映射为 0）。
fn gf_inv(a: u8) -> u8 {
    if a == 0 {
        return 0;
    }
    // a^254 == a^-1（费马小定理）
    let mut r = a;
    for _ in 1..254 {
        r = gf_mul(r, a);
    }
    r
}

/// S-box 仿射变换（FIPS 197：循环左移，非普通移位）。
fn affine(a: u8) -> u8 {
    a ^ a.rotate_left(1) ^ a.rotate_left(2) ^ a.rotate_left(3) ^ a.rotate_left(4) ^ 0x63
}

fn rcon(i: u32) -> u8 {
    let mut v = 1u8;
    for _ in 0..i {
        let hi = v & 0x80;
        v <<= 1;
        if hi != 0 {
            v ^= 0x1b;
        }
    }
    v
}

/// AES-256 密钥扩展：返回 15 轮密钥（每轮 16 字节）。
fn expand_key(key: &[u8; 32]) -> [[u8; 16]; 15] {
    let sbox = sbox_table();
    let mut w = [0u32; 60];
    for i in 0..8 {
        w[i] = u32::from_be_bytes([key[4 * i], key[4 * i + 1], key[4 * i + 2], key[4 * i + 3]]);
    }
    for i in 8..60 {
        let mut temp = w[i - 1];
        if i % 8 == 0 {
            // SubWord(RotWord(temp)) ^ Rcon
            let r = temp.rotate_right(8);
            let b = r.to_be_bytes();
            let sub = [
                sbox[b[0] as usize],
                sbox[b[1] as usize],
                sbox[b[2] as usize],
                sbox[b[3] as usize],
            ];
            temp = u32::from_be_bytes(sub) ^ ((rcon((i / 8) as u32 - 1) as u32) << 24);
        } else if i % 8 == 4 {
            let b = temp.to_be_bytes();
            temp = u32::from_be_bytes([
                sbox[b[0] as usize],
                sbox[b[1] as usize],
                sbox[b[2] as usize],
                sbox[b[3] as usize],
            ]);
        }
        w[i] = w[i - 8] ^ temp;
    }

    let mut rk = [[0u8; 16]; 15];
    for round in 0..15 {
        for i in 0..4 {
            rk[round][i * 4..i * 4 + 4].copy_from_slice(&w[4 * round + i].to_be_bytes());
        }
    }
    rk
}

fn add_round_key(state: &mut [u8; 16], rk: &[u8; 16]) {
    for i in 0..16 {
        state[i] ^= rk[i];
    }
}

fn sub_bytes(state: &mut [u8; 16]) {
    let sbox = sbox_table();
    for b in state.iter_mut() {
        *b = sbox[*b as usize];
    }
}

fn shift_rows(state: &mut [u8; 16]) {
    // 状态按列存储：state[col*4 + row]
    for row in 1..4 {
        for _ in 0..row {
            let t0 = state[row];
            let t1 = state[4 + row];
            let t2 = state[8 + row];
            let t3 = state[12 + row];
            state[row] = t1;
            state[4 + row] = t2;
            state[8 + row] = t3;
            state[12 + row] = t0;
        }
    }
}

fn mix_columns(state: &mut [u8; 16]) {
    for col in 0..4 {
        let (a, b, c, d) = (
            state[col * 4],
            state[col * 4 + 1],
            state[col * 4 + 2],
            state[col * 4 + 3],
        );
        state[col * 4] = gf_mul(a, 2) ^ gf_mul(b, 3) ^ c ^ d;
        state[col * 4 + 1] = a ^ gf_mul(b, 2) ^ gf_mul(c, 3) ^ d;
        state[col * 4 + 2] = a ^ b ^ gf_mul(c, 2) ^ gf_mul(d, 3);
        state[col * 4 + 3] = gf_mul(a, 3) ^ b ^ c ^ gf_mul(d, 2);
    }
}

/// AES-256 单块加密。
fn encrypt_block(rk: &[[u8; 16]; 15], block: &mut [u8; 16]) {
    add_round_key(block, &rk[0]);
    for round in 1..14 {
        sub_bytes(block);
        shift_rows(block);
        mix_columns(block);
        add_round_key(block, &rk[round]);
    }
    sub_bytes(block);
    shift_rows(block);
    add_round_key(block, &rk[14]);
}

// ---------- CBC + PKCS#7 ----------

fn pkcs7_pad(data: &[u8]) -> Vec<u8> {
    let pad = 16 - (data.len() % 16);
    let mut out = data.to_vec();
    out.extend(std::iter::repeat(pad as u8).take(pad));
    out
}

fn pkcs7_unpad(data: &[u8]) -> Option<Vec<u8>> {
    if data.is_empty() || data.len() % 16 != 0 {
        return None;
    }
    let pad = *data.last()? as usize;
    if pad == 0 || pad > 16 {
        return None;
    }
    if data[data.len() - pad..].iter().any(|&b| b as usize != pad) {
        return None;
    }
    Some(data[..data.len() - pad].to_vec())
}

fn cbc_encrypt(rk: &[[u8; 16]; 15], iv: &[u8; 16], data: &[u8]) -> Vec<u8> {
    let padded = pkcs7_pad(data);
    let mut out = Vec::with_capacity(padded.len());
    let mut prev = *iv;
    for chunk in padded.chunks_exact(16) {
        let mut block: [u8; 16] = chunk.try_into().unwrap();
        for i in 0..16 {
            block[i] ^= prev[i];
        }
        encrypt_block(rk, &mut block);
        prev = block;
        out.extend_from_slice(&block);
    }
    out
}

fn cbc_decrypt(rk: &[[u8; 16]; 15], iv: &[u8; 16], data: &[u8]) -> Option<Vec<u8>> {
    if data.is_empty() || data.len() % 16 != 0 {
        return None;
    }
    // 解密所需的逆 S-box 与逆列混合
    let sbox = sbox_table();
    let mut inv_sbox = [0u8; 256];
    for (i, &b) in sbox.iter().enumerate() {
        inv_sbox[b as usize] = i as u8;
    }

    let mut out = Vec::with_capacity(data.len());
    let mut prev = *iv;
    for chunk in data.chunks_exact(16) {
        let mut block: [u8; 16] = chunk.try_into().unwrap();
        decrypt_block_inner(rk, &mut block, &inv_sbox);
        for i in 0..16 {
            block[i] ^= prev[i];
        }
        prev = chunk.try_into().unwrap();
        out.extend_from_slice(&block);
    }
    pkcs7_unpad(&out)
}

fn decrypt_block_inner(rk: &[[u8; 16]; 15], block: &mut [u8; 16], inv_sbox: &[u8; 256]) {
    add_round_key(block, &rk[14]);
    for round in (1..14).rev() {
        inv_shift_rows(block);
        inv_sub_bytes(block, inv_sbox);
        add_round_key(block, &rk[round]);
        inv_mix_columns(block);
    }
    inv_shift_rows(block);
    inv_sub_bytes(block, inv_sbox);
    add_round_key(block, &rk[0]);
}

fn inv_sub_bytes(state: &mut [u8; 16], inv_sbox: &[u8; 256]) {
    for b in state.iter_mut() {
        *b = inv_sbox[*b as usize];
    }
}

fn inv_shift_rows(state: &mut [u8; 16]) {
    for row in 1..4 {
        for _ in 0..row {
            let t0 = state[row];
            let t1 = state[4 + row];
            let t2 = state[8 + row];
            let t3 = state[12 + row];
            state[row] = t3;
            state[4 + row] = t0;
            state[8 + row] = t1;
            state[12 + row] = t2;
        }
    }
}

fn inv_mix_columns(state: &mut [u8; 16]) {
    for col in 0..4 {
        let (a, b, c, d) = (
            state[col * 4],
            state[col * 4 + 1],
            state[col * 4 + 2],
            state[col * 4 + 3],
        );
        state[col * 4] = gf_mul(a, 14) ^ gf_mul(b, 11) ^ gf_mul(c, 13) ^ gf_mul(d, 9);
        state[col * 4 + 1] = gf_mul(a, 9) ^ gf_mul(b, 14) ^ gf_mul(c, 11) ^ gf_mul(d, 13);
        state[col * 4 + 2] = gf_mul(a, 13) ^ gf_mul(b, 9) ^ gf_mul(c, 14) ^ gf_mul(d, 11);
        state[col * 4 + 3] = gf_mul(a, 11) ^ gf_mul(b, 13) ^ gf_mul(c, 9) ^ gf_mul(d, 14);
    }
}

// ---------- 高级接口（Seal / Open） ----------

/// 加密并认证：输出 `iv(16) || ciphertext`，另附 HMAC-SHA256(enc_key, iv||ct)。
/// 返回 (sealed, mac)。sealed 已含 IV 前缀。
pub fn seal(key: &[u8; 32], plaintext: &[u8]) -> (Vec<u8>, [u8; 32]) {
    let rk = expand_key(key);
    // 随机 IV：以时间纳秒与密钥键控派生，避免 IV 复用
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos()
        .to_le_bytes();
    let mut seed = Vec::with_capacity(key.len() + nanos.len());
    seed.extend_from_slice(key);
    seed.extend_from_slice(&nanos);
    let digest = sha256(&seed);
    let mut iv = [0u8; 16];
    iv.copy_from_slice(&digest[..16]);
    let ct = cbc_encrypt(&rk, &iv, plaintext);
    let mut sealed = Vec::with_capacity(16 + ct.len());
    sealed.extend_from_slice(&iv);
    sealed.extend_from_slice(&ct);
    let mac = hmac_sha256(key, &sealed);
    (sealed, mac)
}

/// 校验 MAC 并解密。失败（篡改/错误密钥）返回 None。
pub fn open(key: &[u8; 32], sealed: &[u8], mac: &[u8; 32]) -> Option<Vec<u8>> {
    let expected = hmac_sha256(key, sealed);
    if !constant_time_eq(&expected, mac) || sealed.len() < 16 {
        return None;
    }
    let mut iv = [0u8; 16];
    iv.copy_from_slice(&sealed[..16]);
    let rk = expand_key(key);
    cbc_decrypt(&rk, &iv, &sealed[16..])
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

/// 从 hex 解析 32 字节密钥。
pub fn parse_key(hex_str: &str) -> Option<[u8; 32]> {
    if hex_str.len() != 64 {
        return None;
    }
    let mut k = [0u8; 32];
    for i in 0..32 {
        k[i] = u8::from_str_radix(&hex_str[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(k)
}

/// 从任意种子派生 32 字节密钥（HMAC 键控派生）。
pub fn derive_key(seed: &[u8]) -> [u8; 32] {
    let mut k = [0u8; 32];
    let h = hmac_sha256(b"pacc-v4-local-seal", seed);
    k.copy_from_slice(&h);
    k
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sha256::hex;

    #[test]
    fn aes256_known_answer() {
        // NIST AES-256 已知答案测试（密钥=0x00.., 明文=0x00.., 期望密文）
        let key = [0u8; 32];
        let mut block = [0u8; 16];
        let rk = expand_key(&key);
        encrypt_block(&rk, &mut block);
        assert_eq!(
            hex(&block),
            "dc95c078a2408989ad48a21492842087"
        );
    }

    #[test]
    fn seal_open_roundtrip() {
        let key = derive_key(b"test-seed");
        let msg = b"pacc detection event payload";
        let (sealed, mac) = seal(&key, msg);
        let opened = open(&key, &sealed, &mac).unwrap();
        assert_eq!(opened, msg);

        // 篡改检测
        let mut tampered = sealed.clone();
        let last = tampered.len() - 1;
        tampered[last] ^= 0x01;
        assert!(open(&key, &tampered, &mac).is_none());

        // 错误密钥
        let bad = derive_key(b"wrong-seed");
        assert!(open(&bad, &sealed, &mac).is_none());
    }

    #[test]
    fn parse_key_works() {
        let k = parse_key(&"00".repeat(32)).unwrap();
        assert_eq!(k, [0u8; 32]);
        assert!(parse_key("abc").is_none());
    }
}
