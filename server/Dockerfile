# OnnShell library server for TrueNAS, CasaOS or any Docker host. Python stdlib only.
# Build from the repo root:
#   docker build -f server/Dockerfile -t onnshell-library .
# Run, with your library folder mounted at /library:
#   docker run -d --name onnshell-library --restart unless-stopped -p 8765:8765 \
#     -v /mnt/pool/games:/library -e ONNSHELL_TOKEN=change-me onnshell-library
# Then in OnnShell: System > Library sync > Server, http://<this host>:8765 (and the token, if set).
FROM python:3.12-alpine
WORKDIR /app
COPY server/server.py .
EXPOSE 8765
VOLUME /library
CMD ["python", "/app/server.py", "--library", "/library"]
