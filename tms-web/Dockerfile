FROM nginx:alpine

# 设置时区为上海
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata openssl && \
    ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo $TZ > /etc/timezone

# 自签证书：构建时生成并打进镜像。换 IP：docker build --build-arg WEB_HOST_IP=172.31.83.x ...
ARG WEB_HOST_IP=172.31.83.206
RUN mkdir -p /etc/nginx/certs && \
    openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
      -keyout /etc/nginx/certs/tms.key -out /etc/nginx/certs/tms.crt \
      -subj "/CN=TMS" \
      -addext "subjectAltName=IP:${WEB_HOST_IP},DNS:localhost"

COPY dist /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf

# 端口用 -p 发布：-p 443:443 -p 80:80
EXPOSE 80 443

CMD ["nginx", "-g", "daemon off;"]
