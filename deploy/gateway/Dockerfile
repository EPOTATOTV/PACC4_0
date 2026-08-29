# PACC v4.0 域名网关
# 统一入口：admin / api / pacc / dl 四个子域名路由
FROM nginx:1.25-alpine

# 下载站静态资源（dl.potatotv.asia）
COPY deploy/dl-web /usr/share/nginx/dl

# nginx 主配置 + 站点配置
COPY deploy/gateway/nginx.conf /etc/nginx/nginx.conf
COPY deploy/gateway/conf.d /etc/nginx/conf.d.default

# 入口脚本：若已放置正式证书则启用 HTTPS，否则仅 HTTP
COPY deploy/gateway/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh \
    && mkdir -p /etc/nginx/certs /etc/nginx/conf.d /var/www/certbot

EXPOSE 80 443

ENTRYPOINT ["/entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
