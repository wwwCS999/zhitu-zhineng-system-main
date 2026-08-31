# Aliyun Deployment

This folder contains a standard non-Docker deployment setup for Alibaba Cloud ECS.

## Architecture

- Frontend: Nginx static site at `/var/www/zhitu-system`
- Backend: Spring Boot service on `127.0.0.1:8080`
- Public entry: Nginx `80/443`, proxying `/api` to backend
- Secrets: `/opt/zhitu-system/.env`

## Server Prerequisites

Install these on the ECS instance:

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven nginx git rsync curl

# Node.js 18+ is required. One common choice:
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
```

Create a runtime user:

```bash
sudo useradd -r -m -d /opt/zhitu-system -s /usr/sbin/nologin zhitu || true
```

## First Deploy

```bash
sudo mkdir -p /opt/zhitu-system
sudo chown -R $USER:$USER /opt/zhitu-system
git clone YOUR_REPOSITORY_URL /opt/zhitu-system
cd /opt/zhitu-system

cp deploy/aliyun/env.production.example .env
nano .env

chmod +x deploy/aliyun/deploy.sh
./deploy/aliyun/deploy.sh
```

After deployment:

```bash
sudo systemctl status zhitu-backend
sudo journalctl -u zhitu-backend -f
curl http://127.0.0.1:8080/api/agent/status
```

## Aliyun Security Group

Open:

- 22 for SSH
- 80 for HTTP
- 443 for HTTPS, if you configure TLS

Do not expose `8080` publicly. Let Nginx proxy to it locally.

## Notes

- Keep `.env` only on the server. Never commit real API keys.
- Use Alibaba Cloud RDS or a private VPC MySQL for production data.
- If you add a real domain, replace `server_name _;` in `nginx.zhitu.conf`.
