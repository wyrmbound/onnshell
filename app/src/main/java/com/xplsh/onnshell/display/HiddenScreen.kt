package com.xplsh.onnshell.display

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.util.Log
import android.view.Surface

/**
 * Shizuku user service, shell uid (owner ask, 2026-11-11: picture in picture for every installed game, "hidden screen").
 * This TV build has no multi-display feature, so `am start --display` and moveRootTaskToDisplay refuse a virtual display;
 * but a root task the window organizer creates on it accepts a reparented game task (spike 2026-11-11, Pokemon Red).
 * The display has its own focus, and the controller copy inputd makes is tied to it, so the remote keeps the TV.
 * No DESTROY_CONTENT_ON_REMOVAL: if this process dies, Android moves the game back to the TV instead of closing it.
 */
class HiddenScreen : IHiddenScreen.Stub() {
    private var vd: VirtualDisplay? = null
    private var root: Any? = null
    private var rootId = -1
    private var gameId = -1
    private var port: String? = null
    private var gameUid = -1

    override fun destroy() { leave(); System.exit(0) }

    @Synchronized override fun enter(surface: Surface, pkg: String, width: Int, height: Int, dpi: Int, inputPort: String): String =
        runCatching {
            if (vd != null) return "err already in the corner"
            gameId = tasks().firstOrNull { it.pkg == pkg && it.display == 0 }?.id ?: return "err no task for $pkg"
            gameUid = runCatching { sys().packageManager.getPackageUid(pkg, 0) }.getOrDefault(-1)
            // OWN_CONTENT_ONLY 8, SUPPORTS_TOUCH 64 (without it Android makes no input viewport, so the controller
            // copy tied to this display had nowhere to go and fell through to the TV), TRUSTED 1024, OWN_FOCUS 16384,
            // STEAL_TOP_FOCUS_DISABLED 65536: a controller press must not make this the focused display, or the
            // remote's arrows went to the game instead of the app on the TV (owner report, 2026-11-11)
            val d = dm().createVirtualDisplay("onnshell-hidden", width, height, dpi, surface, 8 or 64 or 1024 or 16384 or 65536)
            vd = d
            val did = d.display.displayId
            toc().javaClass.methods.first { it.name == "createRootTask" }.let { m ->
                m.invoke(toc(), *arrayOf<Any?>(did, 1, Binder(), false).copyOf(m.parameterTypes.size))
            }
            val info = (atm().javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType).invoke(atm(), did) as List<*>)
                .firstOrNull() ?: return fail("no root task on the hidden screen")
            root = info.javaClass.getField("token").get(info); rootId = info.javaClass.getField("taskId").getInt(info)
            reparent(token(gameId) ?: return fail("game task gone"), root)
            if (tasks().firstOrNull { it.id == gameId }?.display != did) return fail("game did not move")
            val uid = d.display.javaClass.getMethod("getUniqueId").invoke(d.display) as String
            im().javaClass.getMethod("addUniqueIdAssociation", String::class.java, String::class.java).invoke(im(), inputPort, uid)
            port = inputPort
            Log.i(TAG, "game task $gameId on hidden display $did ($uid)")
            "ok"
        }.getOrElse { fail(it.toString()) }

    /**
     * Game sound off or on, by rerouting: an audio policy with a loop-back mix that matches the game's uid sends its
     * sound into Android's remote-submix pipe instead of the speakers, and we read that pipe and drop it (the pipe's
     * output side only opens while something records from it). Why not volume: a shared AAudio (MMAP) stream ignores
     * player volume, pause and the PLAY_AUDIO app op on this Android build (Pokemon Red, 2026-11-11), and only system
     * processes may set a hardware channel's volume. The reroute makes Android cut the game's current audio stream:
     * AudioTrack games carry on unnoticed, AAudio games must reopen theirs (OpenAL Soft does: "Reopened device");
     * a game that does not stays silent until it is restarted. If this process dies, Android removes the policy itself.
     */
    private var policy: Any? = null
    private var sink: android.media.AudioRecord? = null

    @Synchronized override fun setMuted(on: Boolean) {
        if (on == (policy != null)) return
        if (!on) { unmute(); return }
        // as ourselves (shell), not as the OnnShell app that called in: the policy checks the caller's permissions
        val me = Binder.clearCallingIdentity()
        runCatching {
            val d = "$"
            val ruleC = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBC = Class.forName("android.media.audiopolicy.AudioMixingRule${d}Builder")
            val rb = ruleBC.getConstructor().newInstance()
            ruleBC.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java).invoke(rb, ruleC.getField("RULE_MATCH_UID").getInt(null), gameUid)
            val mixC = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBC = Class.forName("android.media.audiopolicy.AudioMix${d}Builder")
            val mb = mixBC.getConstructor(ruleC).newInstance(ruleBC.getMethod("build").invoke(rb))
            mixBC.getMethod("setFormat", android.media.AudioFormat::class.java).invoke(mb, android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            mixBC.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mb, mixC.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
            val mix = mixBC.getMethod("build").invoke(mb)
            val polC = Class.forName("android.media.audiopolicy.AudioPolicy")
            val polBC = Class.forName("android.media.audiopolicy.AudioPolicy${d}Builder")
            val pb = polBC.getConstructor(Context::class.java).newInstance(shellCtx())
            polBC.getMethod("addMix", mixC).invoke(pb, mix)
            polBC.getMethod("setLooper", android.os.Looper::class.java).invoke(pb, audioLooper.looper)
            val pol = polBC.getMethod("build").invoke(pb)
            val r = android.media.AudioManager::class.java.getMethod("registerAudioPolicy", polC).invoke(am(), pol)
            if (r != 0) error("registerAudioPolicy returned $r")
            policy = pol
            // What AudioPolicy.createAudioRecordSink builds, but with our own context: its recorder names the process's
            // app package (OnnShell) with the shell uid, and Android refuses that pair ("recording not allowed").
            val attrs = android.media.AudioAttributes.Builder()
            val abC = android.media.AudioAttributes.Builder::class.java
            abC.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType).invoke(attrs, android.media.MediaRecorder.AudioSource.REMOTE_SUBMIX)
            abC.getMethod("addTag", String::class.java).invoke(attrs, "addr=" + mixC.getMethod("getRegistration").invoke(mix))
            abC.getMethod("addTag", String::class.java).invoke(attrs, "fixedVolume")
            val rec = android.media.AudioRecord.Builder()
            rec.javaClass.getMethod("setAudioAttributes", android.media.AudioAttributes::class.java).invoke(rec, attrs.build())
            val ar = rec.setContext(shellCtx()).setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000).setChannelMask(android.media.AudioFormat.CHANNEL_IN_STEREO).build()).build()
            sink = ar
            ar.startRecording()
            if (ar.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING) error("the audio pipe would not start")
            Thread({ val buf = ByteArray(8192); while (runCatching { ar.read(buf, 0, buf.size) }.getOrDefault(-1) > 0) { /* dropped */ } }, "hidden-audio-drop").start()
            Log.i(TAG, "game sound rerouted away from the speakers (uid $gameUid)")
        }.onFailure { Log.w(TAG, "mute failed: ${it.cause ?: it}"); unmute() }
        Binder.restoreCallingIdentity(me)
    }

    private fun unmute() {
        sink?.let { runCatching { it.stop() }; runCatching { it.release() } }; sink = null
        policy?.let { p -> runCatching { android.media.AudioManager::class.java.getMethod("unregisterAudioPolicy", p.javaClass).invoke(am(), p) } }
        policy = null
    }

    private val audioLooper by lazy { android.os.HandlerThread("hidden-audio").apply { start() } }
    private fun am() = android.media.AudioManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(shellCtx())

    @Synchronized override fun leave(): String = runCatching {
        unmute()
        port?.let { p -> runCatching { im().javaClass.getMethod("removeUniqueIdAssociation", String::class.java).invoke(im(), p) } }
        port = null
        val d = vd ?: return "ok"
        if (rootId > 0) {
            // the root task first (always allowed onto the TV), then the game out of it into the TV's own task area
            runCatching {
                atm().javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(atm(), rootId, 0)
            }
            token(gameId)?.let { reparent(it, null) }
            root?.let { r -> runCatching { toc().javaClass.methods.first { it.name == "deleteRootTask" }.invoke(toc(), r) } }
        }
        root = null; rootId = -1; vd = null
        d.release()
        // to the front of the TV, full screen
        if (gameId > 0) runCatching { atm().javaClass.methods.first { it.name == "startActivityFromRecents" }.invoke(atm(), gameId, null) }
        gameId = -1; gameUid = -1
        "ok"
    }.getOrElse { "err $it" }

    /** The game is still on the hidden screen (opening it from the home screen pulls it back to the TV). */
    @Synchronized override fun alive(): Boolean = vd?.let { d -> tasks().any { it.id == gameId && it.display == d.display.displayId } } ?: false

    private fun fail(why: String): String { Log.w(TAG, "enter failed: $why"); leave(); return "err $why" }

    private data class T(val id: Int, val pkg: String?, val display: Int)

    private fun rawTasks(): List<Any> {
        val get = atm().javaClass.methods.first { it.name == "getTasks" }
        return (get.invoke(atm(), *arrayOf<Any?>(50, false, false, -1).copyOf(get.parameterTypes.size)) as List<*>).filterNotNull()
    }

    private fun tasks() = rawTasks().map { t ->
        val c = t.javaClass
        T(c.getField("taskId").getInt(t), (c.getField("baseActivity").get(t) as ComponentName?)?.packageName, c.getField("displayId").getInt(t))
    }

    private fun token(id: Int): Any? =
        rawTasks().firstOrNull { it.javaClass.getField("taskId").getInt(it) == id }?.let { it.javaClass.getField("token").get(it) }

    private fun reparent(task: Any, parent: Any?) {
        val tokC = Class.forName("android.window.WindowContainerToken")
        val wctC = Class.forName("android.window.WindowContainerTransaction")
        val wct = wctC.getConstructor().newInstance()
        wctC.getMethod("reparent", tokC, tokC, Boolean::class.javaPrimitiveType).invoke(wct, task, parent, true)
        val org = Class.forName("android.window.WindowOrganizer").getConstructor().newInstance()
        org.javaClass.getMethod("applyTransaction", wctC).invoke(org, wct)
    }

    private fun atm(): Any = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    private fun toc(): Any = atm().javaClass.getMethod("getWindowOrganizerController").invoke(atm())!!
        .let { it.javaClass.getMethod("getTaskOrganizerController").invoke(it)!! }
    private fun im(): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "input")
        return Class.forName("android.hardware.input.IInputManager" + "$" + "Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
    }

    private fun sys(): Context {
        val atC = Class.forName("android.app.ActivityThread")
        val at = atC.getMethod("currentActivityThread").invoke(null)
            ?: atC.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                .also { atC.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, it) }
        return atC.getDeclaredMethod("getSystemContext").invoke(at) as Context
    }

    /** The system context speaking as com.android.shell (a virtual display's or recorder's owner package must match our uid). */
    private fun shellCtx(): Context = object : ContextWrapper(sys()) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getAttributionSource() =
            android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build()
    }

    private fun dm(): DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(shellCtx())

    private companion object { const val TAG = "HiddenScreen" }
}
