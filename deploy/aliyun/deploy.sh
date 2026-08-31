#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/zhitu-system}"
WEB_DIR="${WEB_DIR:-/var/www/zhitu-system}"
SERVICE_NAME="${SERVICE_NAME:-zhitu-backend}"
NGINX_SITE="${NGINX_SITE:-/etc/nginx/conf.d/zhitu-system.conf}"

if [[ ! -d "$APP_DIR" ]]; then
  echo "[ERROR] APP_DIR does not exist: $APP_DIR"
  echo "Clone or upload the project to $APP_DIR first."
  exit 1
fi

cd "$APP_DIR"

if [[ ! -f "$APP_DIR/.env" ]]; then
  echo "[ERROR] Missing $APP_DIR/.env"
  echo "Copy deploy/aliyun/env.production.example to $APP_DIR/.env and fill secrets first."
  exit 1
fi

echo "[1/6] Build backend"
cd "$APP_DIR/backend"
mvn -DskipTests package

echo "[2/6] Build frontend"
cd "$APP_DIR/frontend"
npm ci || npm install
npm run build

echo "[3/6] Publish frontend to $WEB_DIR"
sudo mkdir -p "$WEB_DIR"
sudo rsync -a --delete "$APP_DIR/frontend/dist/" "$WEB_DIR/"

echo "[4/6] Install backend service"
sudo cp "$APP_DIR/deploy/aliyun/zhitu-backend.service" "/etc/systemd/system/${SERVICE_NAME}.service"
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"

echo "[5/6] Install nginx site"
sudo cp "$APP_DIR/deploy/aliyun/nginx.zhitu.conf" "$NGINX_SITE"
sudo nginx -t
sudo systemctl reload nginx

echo "[6/6] Check status"
systemctl --no-pager --full status "$SERVICE_NAME" || true
curl -fsS http://127.0.0.1:8080/api/agent/status >/dev/null && echo "[OK] Backend API is reachable"

echo "[OK] Deployment finished. Open http://YOUR_SERVER_IP or your configured domain."
