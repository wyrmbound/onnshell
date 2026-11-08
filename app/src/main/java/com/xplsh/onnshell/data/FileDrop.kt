package com.xplsh.onnshell.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * "Send game files": a small upload page on the home network, open only while its screen is on the TV.
 * A phone or PC opens http://<box>:8737/<code> (the QR code) and its files land in [GameFiles.INBOX].
 * Trust boundary: private-network clients only, only under the random code, names cleaned of any path tricks.
 * The box never fetches anything here; it only receives what the owner sends.
 */
object FileDrop {
    const val PORT = 8737
    private const val KEEP_FREE = 200L shl 20 // never fill the box to the last byte
    data class Got(val path: String, val size: Long)

    private var server: ServerSocket? = null
    @Volatile var code = ""; private set
    /** Files received this session, newest last (Compose state, changed on the main thread). */
    val received = mutableStateListOf<Got>()
    private val main by lazy { Handler(Looper.getMainLooper()) } // lazy: unit tests touch this object without Android

    /** Opens the page; returns its address, or null when the box has no home-network address. */
    fun start(): String? {
        stop()
        val ip = lanAddress() ?: return null
        code = newCode()
        received.clear()
        val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        server = ss
        Thread({ while (!ss.isClosed) { val c = runCatching { ss.accept() }.getOrNull() ?: break; Thread { serve(c) }.start() } }, "filedrop").start()
        return "http://${ip.hostAddress}:$PORT/$code"
    }

    fun stop() { runCatching { server?.close() }; server = null; code = "" }

    private fun serve(c: Socket) = runCatching {
        c.use {
            c.soTimeout = 60_000
            if (!isPrivate(c.inetAddress)) return@use
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val request = readLine(input) ?: return@use
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: return@use
                if (h.isEmpty()) break
                val i = h.indexOf(':'); if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val parts = request.split(' ')
            if (parts.size < 2 || code.isEmpty()) return@use respond(out, 404, "not here")
            val path = parts[1].substringBefore('?'); val query = parts[1].substringAfter('?', "")
            when {
                parts[0] == "GET" && path == "/$code" -> respond(out, 200, PAGE, "text/html; charset=utf-8")
                parts[0] == "PUT" && path == "/$code/upload" -> upload(input, out, query, headers["content-length"]?.toLongOrNull())
                else -> respond(out, 404, "not here")
            }
        }
    }

    private fun upload(input: InputStream, out: OutputStream, query: String, len: Long?) {
        val rel = query.split('&').firstOrNull { it.startsWith("path=") }?.substringAfter('=')
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.let { safePath(it) }
        if (rel == null || len == null || len < 0) return respond(out, 400, "bad request")
        GameFiles.INBOX.mkdirs()
        if (len > GameFiles.INBOX.usableSpace - KEEP_FREE) return respond(out, 507, "not enough space on the box")
        val dest = File(GameFiles.INBOX, rel).apply { parentFile?.mkdirs() }
        val tmp = File(dest.path + ".part") // an interrupted upload never looks like a finished file
        var left = len
        tmp.outputStream().use { o ->
            val buf = ByteArray(1 shl 20)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                o.write(buf, 0, n); left -= n
            }
        }
        if (left > 0 || !(dest.delete() || !dest.exists()) || !tmp.renameTo(dest)) { tmp.delete(); return respond(out, 500, "upload incomplete") }
        main.post { received += Got(rel, len) }
        respond(out, 200, "ok")
    }

    private fun respond(out: OutputStream, status: Int, body: String, type: String = "text/plain; charset=utf-8") {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $status X\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val ch = i.read(); if (ch < 0) return null
            if (ch == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(ch.toChar())
        }
        return null
    }

    /** A relative path from the browser ("Folder/sub/file.bin"), cleaned: no "..", no drive or control characters. Null if nothing is left. */
    fun safePath(raw: String): String? {
        val segs = raw.split('/', '\\').map { s -> s.filter { it >= ' ' && it !in ":*?\"<>|" }.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty() || segs.size > 8 || segs.any { it == "." || it == ".." || it.length > 200 }) return null
        return segs.joinToString("/")
    }

    /** Home network only: loopback, 10/8, 172.16/12, 192.168/16, link-local, and IPv6 unique-local. */
    fun isPrivate(a: InetAddress): Boolean = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
        (a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc)

    private fun lanAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }

    private fun newCode(): String {
        val abc = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; val r = SecureRandom()
        return (1..6).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    // The page the phone or PC sees. No "$" in it: this is a Kotlin string.
    private val PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Send game files</title><style>
:root{color-scheme:dark}body{margin:0;font:16px system-ui,sans-serif;background:#16121c;color:#f3eef8}
main{max-width:560px;margin:0 auto;padding:24px 16px}h1{font-size:26px;margin:0 0 6px}p{color:#bfb6cc;line-height:1.45}
.row{display:flex;gap:10px;flex-wrap:wrap;margin:18px 0}
label{flex:1;min-width:150px;text-align:center;padding:16px;border-radius:14px;background:linear-gradient(90deg,#ff4c1d,#e5266e,#7a2ff2);font-weight:700;cursor:pointer}
label.alt{background:#2c2436}input{display:none}
.f{background:#221b2b;border-radius:12px;padding:10px 12px;margin:8px 0}.n{word-break:break-all}.s{font-size:13px;color:#bfb6cc;margin-top:4px}
.bar{height:6px;background:#3a3046;border-radius:3px;margin-top:6px;overflow:hidden}.bar i{display:block;height:100%;width:0;background:#e5266e}
.ok .bar i{background:#4caf50}.bad .s{color:#ff8a80}
</style></head><body><main>
<h1>Send game files</h1>
<p>Pick files from this device and they go straight to your onn box. Zips are fine. The box works out which game each file belongs to.</p>
<div class="row"><label>Choose files<input id="files" type="file" multiple></label>
<label class="alt" id="dirl">Choose a folder<input id="dir" type="file" webkitdirectory multiple></label></div>
<p id="note" style="font-size:14px">Keep this page open until every file says Done.</p>
<div id="list"></div></main><script>
var base=location.pathname.replace(/\/+${'$'}/,'');var queue=[];var busy=false;
if(!('webkitdirectory' in document.createElement('input')))document.getElementById('dirl').style.display='none';
function mb(n){return (n/1048576).toFixed(n<1048576?2:0)+' MB'}
function add(fl){for(var i=0;i<fl.length;i++){var f=fl[i];var d=document.createElement('div');d.className='f';
d.innerHTML='<div class="n"></div><div class="s">Waiting</div><div class="bar"><i></i></div>';
d.querySelector('.n').textContent=f.webkitRelativePath||f.name;document.getElementById('list').appendChild(d);queue.push([f,d]);}next();}
function next(){if(busy||!queue.length)return;busy=true;var it=queue.shift(),f=it[0],d=it[1],s=d.querySelector('.s'),b=d.querySelector('i');
var x=new XMLHttpRequest();x.open('PUT',base+'/upload?path='+encodeURIComponent(f.webkitRelativePath||f.name));
x.upload.onprogress=function(e){if(e.lengthComputable){b.style.width=(100*e.loaded/e.total)+'%';s.textContent=mb(e.loaded)+' of '+mb(e.total)}};
x.onload=function(){if(x.status==200){d.className='f ok';b.style.width='100%';s.textContent='Done, '+mb(f.size)}else{d.className='f bad';s.textContent='Failed: '+x.responseText}busy=false;next()};
x.onerror=function(){d.className='f bad';s.textContent='Failed: the box closed the page or the network dropped';busy=false;next()};
x.send(f);}
document.getElementById('files').onchange=function(){add(this.files);this.value=''};
document.getElementById('dir').onchange=function(){add(this.files);this.value=''};
</script></body></html>"""
}
