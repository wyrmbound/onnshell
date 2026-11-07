# Always-on Shizuku starter for TrueNAS, CasaOS or any Docker host (SPEC Phase 4b, path 2).
# Build from the repo root:
#   docker build -f server/shizuku-helper/Dockerfile -t onnshell-shizuku .
# Run (host network so it reaches the box; the volume keeps its ADB key):
#   docker run -d --name onnshell-shizuku --restart unless-stopped --network host \
#     -e ONN_HOST=192.168.1.50 -v onnshell-adb:/root/.android onnshell-shizuku
# First run only: the TV asks "Allow USB debugging?" for this container's key. Tick
# "Always allow from this computer" and press Allow.
FROM alpine:3.20
RUN apk add --no-cache android-tools
WORKDIR /onnshell
COPY scripts/lib.sh scripts/shizuku-start.sh scripts/shizuku-watch.sh scripts/
ENV ADB=adb ONN_PORT=5555
VOLUME /root/.android
CMD ["sh", "/onnshell/scripts/shizuku-watch.sh"]
