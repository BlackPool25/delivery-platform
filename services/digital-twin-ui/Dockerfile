FROM nginx:alpine

LABEL maintainer="engineering@delivery.app"
LABEL service="digital-twin-ui"

RUN rm -rf /etc/nginx/conf.d/default.conf

COPY services/digital-twin-ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY services/digital-twin-ui/static /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
  CMD curl -f http://localhost:80/health || exit 1
