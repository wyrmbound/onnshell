"""Tests for the library server. Run: python -m unittest server/test_server.py (stdlib only)."""
import hashlib
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import server


def write(folder, name, data):
    os.makedirs(folder, exist_ok=True)
    with open(os.path.join(folder, name), "wb" if isinstance(data, bytes) else "w") as f:
        f.write(data)


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = self.tmp.name
        self.apk = b"PK fake apk bytes " * 100
        write(os.path.join(root, "Game"), "game.apk", self.apk)
        write(os.path.join(root, "Game"), "cover.jpg", b"jpeg")
        write(os.path.join(root, "Game"), "item.json", json.dumps(
            {"id": "org.example.game", "title": "Example", "type": "apk", "version": 3, "file": "game.apk", "cover": "cover.jpg"}))
        write(os.path.join(root, "Data"), "pack.zip", b"zipzip")
        write(os.path.join(root, "Data"), "item.json", json.dumps(
            {"id": "example-data", "type": "data", "version": 1, "file": "pack.zip",
             "target": "Android/data/{requires}/files", "requires": "org.example.game"}))
        # Broken items are skipped, never served
        write(os.path.join(root, "Escape"), "item.json", json.dumps(
            {"id": "x", "type": "apk", "version": 1, "file": "../Game/game.apk"}))
        write(os.path.join(root, "BadTarget"), "f.bin", b"x")
        write(os.path.join(root, "BadTarget"), "item.json", json.dumps(
            {"id": "y", "type": "data", "version": 1, "file": "f.bin", "target": "../../etc"}))
        self.start(None)

    def start(self, token):
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(server.Library(self.tmp.name), token))
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.tmp.cleanup()

    def get(self, path, headers=None):
        return urllib.request.urlopen(urllib.request.Request(self.base + path, headers=headers or {}))

    def test_index_lists_valid_items_with_size_and_sha(self):
        index = json.load(self.get("/index.json"))
        items = {i["id"]: i for i in index["items"]}
        self.assertEqual({"org.example.game", "example-data"}, set(items))
        game = items["org.example.game"]
        self.assertEqual(len(self.apk), game["size"])
        self.assertEqual(hashlib.sha256(self.apk).hexdigest(), game["sha256"])
        self.assertEqual("/covers/org.example.game", game["cover"])
        self.assertEqual("game.apk", game["name"])
        self.assertTrue(game["game"])
        self.assertEqual("org.example.game", items["example-data"]["requires"])
        self.assertEqual(2, len(index["problems"]))  # the escape and the bad target

    def test_file_and_resume_range(self):
        self.assertEqual(self.apk, self.get("/files/org.example.game").read())
        r = self.get("/files/org.example.game", {"Range": "bytes=10-"})
        self.assertEqual(206, r.status)
        self.assertEqual(self.apk[10:], r.read())
        self.assertEqual(f"bytes 10-{len(self.apk) - 1}/{len(self.apk)}", r.headers["Content-Range"])

    def test_range_past_the_end_is_416(self):
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.get("/files/org.example.game", {"Range": f"bytes={len(self.apk)}-"})
        self.assertEqual(416, e.exception.code)

    def test_unknown_id_is_404(self):
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.get("/files/x")
        self.assertEqual(404, e.exception.code)

    def test_token(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.start("s3cret")
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.get("/index.json")
        self.assertEqual(401, e.exception.code)
        self.assertEqual(200, self.get("/index.json", {"X-Token": "s3cret"}).status)

    def test_lan_only(self):
        for ok in ("127.0.0.1", "192.168.1.5", "172.16.0.3", "10.0.0.2", "fe80::1", "::ffff:192.168.1.9"):
            self.assertTrue(server.lan_client(ok), ok)
        for bad in ("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "nonsense"):
            self.assertFalse(server.lan_client(bad), bad)

    def test_parse_range(self):
        self.assertIsNone(server.parse_range(None, 100))
        self.assertEqual((0, 99), server.parse_range("bytes=0-", 100))
        self.assertEqual((90, 99), server.parse_range("bytes=-10", 100))
        self.assertEqual((5, 9), server.parse_range("bytes=5-9", 100))
        self.assertEqual("bad", server.parse_range("bytes=5-1", 100))
        self.assertEqual("bad", server.parse_range("items=1-2", 100))


if __name__ == "__main__":
    unittest.main()
