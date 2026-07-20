FROM nginx:alpine

# 设置时区为上海
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata openssl && \
    ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo $TZ > /etc/timezone

# 自签证书：构建时生成并打进镜像，运行时无需再挂载。
# 换内网访问 IP（让证书 SAN 匹配，减少浏览器告警）：
#   docker build --build-arg WEB_HOST_IP=172.31.83.x -t tms-web:latest tms-web/
# 注意：私钥会随镜像分发，内网自用可接受；换证书需重新构建镜像。
ARG WEB_HOST_IP=172.31.83.206
RUN mkdir -p /etc/nginx/certs && \
    openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
      -keyout /etc/nginx/certs/tms.key -out /etc/nginx/certs/tms.crt \
      -subj "/CN=TMS" \
      -addext "subjectAltName=IP:${WEB_HOST_IP},DNS:localhost"

COPY dist /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf

# 端口在 docker run / compose 用 -p 发布：-p 443:443 -p 80:80
EXPOSE 80 443

CMD ["nginx", "-g", "daemon off;"]
