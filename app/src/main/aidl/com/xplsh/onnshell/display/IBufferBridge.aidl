package com.xplsh.onnshell.display;

import android.hardware.HardwareBuffer;

/** Shizuku user service (shell uid): hands app-owned HardwareBuffers to the 64-bit renderer as dma-buf fds. */
interface IBufferBridge {
    /** Shizuku calls this on unbind. */
    void destroy() = 16777114;

    /**
     * Extracts each buffer's dma-buf fd and byte stride and sends them with SCM_RIGHTS to the renderer
     * listening on abstract unix socket @sockName. Returns 0, or -errno-style negative on failure.
     */
    int offer(in HardwareBuffer[] buffers, String sockName, String libPath, String apkPath) = 1;
}
