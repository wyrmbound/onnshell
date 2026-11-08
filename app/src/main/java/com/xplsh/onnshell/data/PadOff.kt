package com.xplsh.onnshell.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.AttributionSource
import android.os.Looper
import android.os.Process

/**
 * Not part of the app: Inputd.padOff runs this as the shell user with app_process, because only a holder of
 * BLUETOOTH_PRIVILEGED (shell has it, OnnShell does not) may drop a device's link. A DS4 turns off when its host
 * disconnects it. Usage: app_process /system/bin com.xplsh.onnshell.data.PadOff AA:BB:CC:DD:EE:FF
 */
object PadOff {
    @JvmStatic fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        // App processes get this at startup (SystemServiceRegistry); a bare app_process does not, and Android 14's
        // BluetoothAdapter then finds no Bluetooth service at all
        val bsm = Class.forName("android.os.BluetoothServiceManager").getDeclaredConstructor().newInstance()
        Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
            .getMethod("setBluetoothServiceManager", bsm.javaClass).invoke(null, bsm)
        // No app context in app_process: speak for the shell package (our uid), as the platform's own shell commands do
        val src = AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
        val adapter = BluetoothAdapter::class.java.getMethod("createAdapter", AttributionSource::class.java)
            .invoke(null, src) as BluetoothAdapter
        val dev = adapter.getRemoteDevice(args[0].uppercase())
        val r = BluetoothDevice::class.java.getMethod("disconnect").invoke(dev) // @SystemApi, Android 13+
        println("disconnect ${dev.address}: $r") // 0 = BluetoothStatusCodes.SUCCESS
    }
}
