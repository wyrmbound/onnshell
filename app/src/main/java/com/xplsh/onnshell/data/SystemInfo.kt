package com.xplsh.onnshell.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import com.xplsh.onnshell.R
import com.xplsh.onnshell.admin.Policy
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Lines for the System info screens. All of these are reads, call them off the main thread. */
object SystemInfo {
    private data class Link(val iface: String, val transport: String, val addrs: List<String>)

    private fun links(ctx: Context): List<Link> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") // allNetworks is the simple way to list every link, including Ethernet
        return cm.allNetworks.mapNotNull { n ->
            val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
            val caps = cm.getNetworkCapabilities(n)
            val t = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                else -> "Other"
            }
            Link(lp.interfaceName ?: "?", t, lp.linkAddresses.map { it.address.hostAddress ?: "" }.filter { "." in it })
        }
    }

    fun ethernet(ctx: Context): List<String> {
        val eth = links(ctx).filter { it.transport == "Ethernet" }
        return if (eth.isEmpty()) listOf(ctx.getString(R.string.eth_none))
        else eth.map { "${it.iface}: ${it.addrs.joinToString().ifEmpty { "-" }}" }
    }

    fun adb(ctx: Context): List<String> {
        val cr = ctx.contentResolver
        val listening = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 5555), 300) } }.isSuccess
        fun onOff(key: String) = ctx.getString(if (Settings.Global.getInt(cr, key, 0) == 1) R.string.on else R.string.off)
        return links(ctx).filter { it.addrs.isNotEmpty() }.map { "${it.transport} (${it.iface}): ${it.addrs.joinToString()}" } +
            listOf(
                ctx.getString(R.string.adb_usb, onOff(Settings.Global.ADB_ENABLED)),
                ctx.getString(R.string.adb_port, ctx.getString(if (listening) R.string.adb_open else R.string.adb_closed)),
                ctx.getString(R.string.adb_wireless, onOff("adb_wifi_enabled")),
                "Shizuku: ${ShellUser.status()}",
            )
    }

    fun storage(ctx: Context): List<String> {
        fun line(name: String, dir: File): String {
            val s = StatFs(dir.path)
            return ctx.getString(R.string.storage_line, name, s.availableBytes / 1e9, s.totalBytes / 1e9)
        }
        val usb = ctx.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.isRemovable }
            .mapNotNull { v -> v.directory?.let { line(v.getDescription(ctx), it) } }
        return listOf(line(ctx.getString(R.string.storage_internal), Environment.getDataDirectory())) +
            usb.ifEmpty { listOf(ctx.getString(R.string.storage_no_usb)) }
    }

    fun about(ctx: Context): List<String> {
        val v = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        return listOf(
            "OnnShell $v",
            "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            Build.FINGERPRINT,
            Policy.status(ctx),
        )
    }
}
