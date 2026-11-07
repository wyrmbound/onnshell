#!/usr/bin/env python3
"""OnnShell library server: native Android game APKs and their data packs, served to the box over the LAN.

Python 3 standard library only. Run on a PC, or in Docker on TrueNAS or CasaOS (see Dockerfile).

Library layout, one folder per item (any folder names, any depth):

    LIBRARY/My Game/item.json
    LIBRARY/My Game/mygame.apk
    LIBRARY/My Game/cover.jpg

item.json fields:
    id        package name for an apk, any short name for data (letters, digits, . _ -)
    title     shown on the TV
    type      "apk" or "data"
    version   integer; for an apk its versionCode, for data any number you raise on change
    file      the payload file in the same folder (a data pack may be a .zip, unpacked on the box)
    cover     optional image in the same folder
    target    data only: where the files go, relative to shared storage, "{requires}" is replaced
              by the required app's id, e.g. "Android/data/{requires}/files"
    requires  data only: the apk id this pack belongs to
    game      optional, default true: show it in the Games row

GET /index.json         {"items": [...]} with size and sha256 added (sha cached by size and mtime)
GET /files/<id>         the payload, with Range support so the box can resume
GET /covers/<id>        the cover image

Only LAN and loopback clients are served. With --token (or ONNSHELL_TOKEN), requests must send the
same value in the X-Token header.
"""
import argparse
import hashlib
import ipaddress
import json
import os
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ID = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
TARGET = re.compile(r"^[A-Za-z0-9._ {}/-]{1,200}$")
CACHE_NAME = ".onnshell-cache.json"


def lan_client(address):
    """True for private, loopback and link local addresses: the server never talks to the internet."""
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_private or ip.is_loopback or ip.is_link_local


def inside(folder, name):
    """Path of name inside folder, or None if it would escape the folder."""
    if not isinstance(name, str) or not name:
        return None
    path = os.path.realpath(os.path.join(folder, name))
    root = os.path.realpath(folder)
    return path if os.path.commonpath([path, root]) == root and os.path.isfile(path) else None


def check_item(meta, folder):
    """Validated item and its file paths, or (None, reason)."""
    if not isinstance(meta, dict):
        return None, "item.json is not an object"
    item_id, kind = meta.get("id"), meta.get("type")
    if not isinstance(item_id, str) or not ID.match(item_id):
        return None, "bad or missing id"
    if kind not in ("apk", "data"):
        return None, "type must be apk or data"
    if not isinstance(meta.get("version"), int) or isinstance(meta.get("version"), bool):
        return None, "version must be an integer"
    payload = inside(folder, meta.get("file"))
    if not payload:
        return None, "file missing or outside its folder"
    item = {
        "id": item_id,
        "title": str(meta.get("title") or item_id),
        "type": kind,
        "version": meta["version"],
        "game": meta.get("game", True) is not False,
    }
    if kind == "data":
        target, requires = meta.get("target"), meta.get("requires")
        if not isinstance(target, str) or not TARGET.match(target) or target.startswith("/") or ".." in target.split("/"):
            return None, "data needs a relative target without .."
        if requires is not None and (not isinstance(requires, str) or not ID.match(requires)):
            return None, "bad requires"
        item["target"] = target
        if requires:
            item["requires"] = requires
    cover = inside(folder, meta.get("cover")) if meta.get("cover") else None
    return {"item": item, "file": payload, "cover": cover}, None


class Library:
    def __init__(self, root):
        self.root = os.path.abspath(root)
        self.lock = threading.Lock()
        self.cache_path = os.path.join(self.root, CACHE_NAME)
        try:
            with open(self.cache_path, encoding="utf-8") as f:
                self.cache = json.load(f)
        except (OSError, ValueError):
            self.cache = {}

    def sha256(self, path):
        st = os.stat(path)
        key = os.path.relpath(path, self.root)
        hit = self.cache.get(key)
        if hit and hit[0] == st.st_size and hit[1] == st.st_mtime:
            return hit[2]
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for block in iter(lambda: f.read(1 << 20), b""):
                h.update(block)
        self.cache[key] = [st.st_size, st.st_mtime, h.hexdigest()]
        return self.cache[key][2]

    def scan(self):
        """Walks the library. Returns (entries by id, problems). Picks up changes on every call."""
        entries, problems = {}, []
        with self.lock:
            for folder, _, names in os.walk(self.root):
                if "item.json" not in names:
                    continue
                try:
                    with open(os.path.join(folder, "item.json"), encoding="utf-8") as f:
                        meta = json.load(f)
                except (OSError, ValueError) as e:
                    problems.append(f"{folder}: {e}")
                    continue
                found, why = check_item(meta, folder)
                if not found:
                    problems.append(f"{folder}: {why}")
                    continue
                item = found["item"]
                if item["id"] in entries:
                    problems.append(f"{folder}: duplicate id {item['id']}")
                    continue
                item["name"] = os.path.basename(found["file"])
                item["size"] = os.path.getsize(found["file"])
                item["sha256"] = self.sha256(found["file"])
                item["file"] = f"/files/{item['id']}"
                if found["cover"]:
                    item["cover"] = f"/covers/{item['id']}"
                entries[item["id"]] = {"item": item, "file": found["file"], "cover": found["cover"]}
            try:
                with open(self.cache_path, "w", encoding="utf-8") as f:
                    json.dump(self.cache, f)
            except OSError:
                pass  # a read only library still works, it just hashes again next time
        return entries, problems


def parse_range(header, size):
    """(start, end) inclusive for a single "bytes=a-b" range, None for no range, or "bad"."""
    if not header:
        return None
    m = re.fullmatch(r"bytes=(\d*)-(\d*)", header.strip())
    if not m or (not m.group(1) and not m.group(2)):
        return "bad"
    if m.group(1):
        start = int(m.group(1))
        end = int(m.group(2)) if m.group(2) else size - 1
    else:  # suffix range: the last n bytes
        start, end = max(0, size - int(m.group(2))), size - 1
    if start >= size or end < start:
        return "bad"
    return start, min(end, size - 1)


def make_handler(library, token):
    class Handler(BaseHTTPRequestHandler):
        server_version = "OnnShellLibrary/1"

        def log_message(self, fmt, *args):
            pass  # quiet; errors are still sent to the client

        def deny(self, code, text):
            body = text.encode()
            self.send_response(code)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if not lan_client(self.client_address[0]):
                return self.deny(403, "LAN only")
            if token and self.headers.get("X-Token") != token:
                return self.deny(401, "token required")
            path = self.path.split("?", 1)[0]
            entries, problems = library.scan()
            if path == "/index.json":
                body = json.dumps({"items": [e["item"] for e in entries.values()], "problems": problems}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                return self.wfile.write(body)
            m = re.fullmatch(r"/(files|covers)/([A-Za-z0-9._-]+)", path)
            entry = entries.get(m.group(2)) if m else None
            target = entry and (entry["file"] if m.group(1) == "files" else entry["cover"])
            if not target:
                return self.deny(404, "not found")
            self.send_file(target)

        def send_file(self, path):
            size = os.path.getsize(path)
            r = parse_range(self.headers.get("Range"), size)
            if r == "bad":
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return
            start, end = r if r else (0, size - 1)
            self.send_response(206 if r else 200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if r:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            with open(path, "rb") as f:
                f.seek(start)
                left = end - start + 1
                while left > 0:
                    block = f.read(min(1 << 20, left))
                    if not block:
                        break
                    self.wfile.write(block)
                    left -= len(block)

    return Handler


def main():
    p = argparse.ArgumentParser(description="OnnShell library server (LAN only)")
    p.add_argument("--library", required=True, help="folder with the items")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--token", default=os.environ.get("ONNSHELL_TOKEN") or None)
    a = p.parse_args()
    library = Library(a.library)
    entries, problems = library.scan()
    print(f"OnnShell library: {len(entries)} items from {library.root}, port {a.port}" + (", token on" if a.token else ""))
    for problem in problems:
        print(f"  skipped {problem}")
    ThreadingHTTPServer((a.bind, a.port), make_handler(library, a.token)).serve_forever()


if __name__ == "__main__":
    main()
