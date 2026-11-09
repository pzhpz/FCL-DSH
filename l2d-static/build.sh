#!/data/data/com.termux/files/usr/bin/sh
# 重新构建页面（模型不在构建产物里，只保留一个 models 软链）
set -e
cd "$HOME/l2d-static"

# 先摘掉软链，避免清空 dist 时跟着软链误删真实模型
rm -f dist/models

"$HOME/.l2d-host/node_modules/.bin/vite" build

# 重建软链：dist/models -> ../models（全项目只有一份模型文件）
ln -s ../models dist/models

echo "构建完成 → $HOME/l2d-static/dist"
