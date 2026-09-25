# Web entry point of a version (spec 009): Caddy with automatic HTTPS, the static front, and the production
# compose file that deploy.sh extracts. Build from the repository root: docker build -f deploy/web/Dockerfile .
FROM caddy:2-alpine
LABEL org.opencontainers.image.source="https://github.com/Azertyny/rfid-backend"
COPY front/ /srv/front/
COPY deploy/web/Caddyfile /etc/caddy/Caddyfile
COPY deploy/compose.yml /opt/vegelink/compose.yml
