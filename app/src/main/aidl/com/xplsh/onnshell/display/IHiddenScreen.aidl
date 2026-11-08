package com.xplsh.onnshell.display;

import android.view.Surface;

/** Shizuku user service (shell uid): runs a regular Android game on a hidden screen drawn into OnnShell's corner window. */
interface IHiddenScreen {
    /** Shizuku calls this on unbind. */
    void destroy() = 16777114;

    /** Moves pkg's task onto a new hidden screen that draws into surface; inputs from inputPort go to it. "ok" or "err ...". */
    String enter(in Surface surface, String pkg, int width, int height, int dpi, String inputPort) = 1;

    /** Brings the game back to the TV, full screen, and removes the hidden screen. "ok" or "err ...". */
    String leave() = 2;

    /** False once the game left the hidden screen (opened again from the home screen, or closed). */
    boolean alive() = 3;

    /** Game sound off (true) or back on, while it is in the corner. */
    void setMuted(boolean on) = 4;
}
