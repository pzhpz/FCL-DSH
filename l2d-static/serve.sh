#!/data/data/com.termux/files/usr/bin/sh
# 用自带静态服务器托管 dist/（强制不缓存，换模型刷新即可见）
set -e
cd "$HOME/l2d-static/dist"
exec python3 "$HOME/l2d-static/serve.py" 8000 "$(pwd)"
