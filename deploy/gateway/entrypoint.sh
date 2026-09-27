#!/bin/sh
# PACC 网关入口脚本：
#   - 若 /etc/nginx/certs 中存在正式证书（potatotv.asia.crt/.key），启用 HTTPS 配置
#   - 否则仅启用 HTTP 配置（开箱即用）
set -e

CERT_DIR=/etc/nginx/certs
CONF_DIR=/etc/nginx/conf.d
DEFAULT_CONF_DIR=/etc/nginx/conf.d.default

# 初始化站点配置目录
rm -f ${CONF_DIR}/*.conf
cp ${DEFAULT_CONF_DIR}/00-http.conf ${CONF_DIR}/00-http.conf

if [ -f "${CERT_DIR}/potatotv.asia.crt" ] && [ -f "${CERT_DIR}/potatotv.asia.key" ]; then
    echo "[gateway] 检测到正式证书，启用 HTTPS 配置"
    cp ${DEFAULT_CONF_DIR}/10-https.conf ${CONF_DIR}/10-https.conf
else
    echo "[gateway] 未检测到证书，仅启用 HTTP（生产建议配置 HTTPS，见 deploy/gateway/certs/README.md）"
fi

exec "$@"
