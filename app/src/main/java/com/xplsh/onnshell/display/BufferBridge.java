package com.xplsh.onnshell.display;

import android.hardware.HardwareBuffer;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Shizuku user service: runs as the shell user in a process Shizuku spawns, so it may talk to the
 * 64-bit renderer (also shell) over a unix socket and pass fds, which the app domain may not.
 * Java here, since Shizuku instantiates it by reflection with a no-arg constructor.
 */
public class BufferBridge extends IBufferBridge.Stub {
    private static boolean loaded;

    public BufferBridge() { }

    @Override public void destroy() { System.exit(0); }

    @Override public int offer(HardwareBuffer[] buffers, String sockName, String libPath, String apkPath) throws RemoteException {
        if (!loaded) {
            try { System.load(libPath); loaded = true; }
            catch (Throwable t) {
                // Android 14 keeps native libs inside the APK unless extracted at install; we run as shell,
                // which can read the APK and write /data/local/tmp, so pull the lib out ourselves.
                try { System.load(extract(apkPath)); loaded = true; }
                catch (Throwable u) { Log.e("M4", "load " + libPath + ": " + t + "; extract: " + u); return -2; }
            }
        }
        return send(buffers, sockName);
    }

    private static String extract(String apkPath) throws Exception {
        File out = new File("/data/local/tmp/libbufbridge.so");
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry e = zip.getEntry("lib/" + android.os.Build.SUPPORTED_ABIS[0] + "/libbufbridge.so");
            if (e == null) throw new Exception("no lib entry in " + apkPath);
            try (InputStream in = zip.getInputStream(e); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
        }
        out.setExecutable(true, false);
        return out.getPath();
    }

    private static native int send(HardwareBuffer[] buffers, String sockName);
}
