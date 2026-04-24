#!/bin/bash

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}TMS 前端镜像构建脚本${NC}"
echo -e "${GREEN}========================================${NC}"

# 配置变量
IMAGE_NAME="tms-web"
VERSION="${1:-latest}"
OUTPUT_DIR="/Users/zhangjian/Downloads"

echo -e "\n${YELLOW}镜像版本: ${VERSION}${NC}\n"

# 检查 dist 是否存在
if [ ! -d dist ]; then
    echo -e "${RED}✗ 未找到 dist 目录，请先运行: npm run build${NC}"
    exit 1
fi

# 构建镜像
echo -e "${GREEN}[1/2] 构建前端镜像...${NC}"
docker build -t ${IMAGE_NAME}:${VERSION} .
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ 前端镜像构建成功: ${IMAGE_NAME}:${VERSION}${NC}"
else
    echo -e "${RED}✗ 前端镜像构建失败${NC}"
    exit 1
fi

# 导出镜像为 tar 包
echo -e "\n${GREEN}[2/2] 导出镜像为 tar 包...${NC}"
docker save ${IMAGE_NAME}:${VERSION} | gzip > ${OUTPUT_DIR}/${IMAGE_NAME}-${VERSION}.tar.gz
echo -e "${GREEN}✓ ${OUTPUT_DIR}/${IMAGE_NAME}-${VERSION}.tar.gz${NC}"

# 显示构建结果
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}构建完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "\n${YELLOW}导出文件:${NC}"
ls -lh ${OUTPUT_DIR}/${IMAGE_NAME}-${VERSION}.tar.gz
echo -e "\n${YELLOW}上传到服务器后加载镜像:${NC}"
echo -e "  gunzip -c ${IMAGE_NAME}-${VERSION}.tar.gz | docker load"
echo -e "  docker run -d -p 80:80 --name tms-web ${IMAGE_NAME}:${VERSION}"
