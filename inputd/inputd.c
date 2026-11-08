/*
 * OnnShell inputd: the DS4 touchpad as a real touchscreen.
 *
 * POINTER mode: the touchpad is left to Android, which already drives its own pointer with it.
 * TOUCH mode:   the touchpad is grabbed (EVIOCGRAB) and its fingers are replayed on a virtual
 *               touchscreen made with /dev/uinput, mapped onto a rectangle of the screen.
 * Share + pad click toggles the mode from the controller. The PS button presses HOME, in every app.
 *
 * Runs as the shell user (group uhid can open /dev/uinput, group input can read the pad),
 * started by OnnShell through Shizuku. One line per message:
 *   stdin:  mode touch | mode pointer | screen W H | rect X Y W H | home | idle SECS | quit
 *   stdout: ready | pad PATH W H | nopad | mode NAME | toggle NAME | home | idle MAC | lat AVG_US MAX_US N | err TEXT
 * "idle MAC": the controller has had no real input for an hour (idle SECS changes that, for tests);
 * OnnShell then drops its Bluetooth link, which makes a DS4 turn itself off, like a PS4 does.
 * When stdin closes (OnnShell died) it exits, and closing the fds releases the grab, so the
 * pad is never left stuck.
 *
 * Build: inputd/build.sh (zig cc, static aarch64; the box's kernel is 64-bit).
 */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <linux/uinput.h>

#define LBITS (8 * sizeof(long))
#define NLONGS(n) (((n) + LBITS - 1) / LBITS)
#define SLOTS 10

static int pad = -1, joy = -1, ui = -1, keys = -1;
static int touch_mode = 0, share = 0, slot = 0, announced = 0, grabbed = 0;
static int active[SLOTS];
static int scr_w = 1920, scr_h = 1080;
static int rx = 0, ry = 0, rw = 1920, rh = 1080;
static struct input_absinfo ax, ay;
static char pad_path[64];
static long long lat_sum, lat_max, frame_us;
static int lat_n;
static long long last_input, idle_us = 3600LL * 1000000; /* idle_us: when to report the pad idle */
static int idle_sent;
static char joy_mac[32];

static void out(const char *fmt, ...) {
    va_list a;
    va_start(a, fmt);
    vprintf(fmt, a);
    va_end(a);
    putchar('\n');
    fflush(stdout);
}

static long long now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000000LL + t.tv_nsec / 1000;
}

static int has(int fd, int type, int code) {
    unsigned long bits[NLONGS(KEY_CNT)] = {0};
    if (ioctl(fd, EVIOCGBIT(type, sizeof bits), bits) < 0) return 0;
    return (bits[code / LBITS] >> (code % LBITS)) & 1;
}

static const char *mode_name(void) { return touch_mode ? "touch" : "pointer"; }

static void emit(int type, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof e);
    e.type = type;
    e.code = code;
    e.value = value;
    if (ui >= 0 && write(ui, &e, sizeof e) < 0 && errno != EAGAIN) out("err uinput write %d", errno);
}

/* Virtual touchscreen covering the whole display. INPUT_PROP_DIRECT makes Android treat it as a touchscreen. */
static int make_touchscreen(void) {
    int u = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (u < 0) { out("err uinput open %d", errno); return -1; }
    ioctl(u, UI_SET_EVBIT, EV_KEY);
    ioctl(u, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(u, UI_SET_EVBIT, EV_ABS);
    ioctl(u, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    int axes[][2] = {{ABS_MT_SLOT, SLOTS - 1}, {ABS_MT_TRACKING_ID, 65535},
                     {ABS_MT_POSITION_X, scr_w - 1}, {ABS_MT_POSITION_Y, scr_h - 1}};
    for (unsigned i = 0; i < sizeof axes / sizeof axes[0]; i++) {
        struct uinput_abs_setup s;
        memset(&s, 0, sizeof s);
        s.code = axes[i][0];
        s.absinfo.maximum = axes[i][1];
        ioctl(u, UI_SET_ABSBIT, axes[i][0]);
        ioctl(u, UI_ABS_SETUP, &s);
    }
    struct uinput_setup us;
    memset(&us, 0, sizeof us);
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor = 0x0001;
    us.id.product = 0x0001;
    snprintf(us.name, sizeof us.name, "OnnShell Touch");
    if (ioctl(u, UI_DEV_SETUP, &us) < 0 || ioctl(u, UI_DEV_CREATE) < 0) {
        out("err uinput create %d", errno);
        close(u);
        return -1;
    }
    return u;
}

/*
 * The PS button is HOME everywhere. Android has no such mapping for the DS4, so on a PS press we
 * press KEY_HOMEPAGE (Generic.kl: key 172 HOME) on a one key virtual keyboard. With no letter keys
 * Android does not treat it as a real keyboard, so the on screen keyboard keeps working. Made once
 * at start: a device made at press time is not registered yet and would drop the first press.
 */
static int make_keys(void) {
    int u = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (u < 0) { out("err uinput open %d", errno); return -1; }
    ioctl(u, UI_SET_EVBIT, EV_KEY);
    ioctl(u, UI_SET_KEYBIT, KEY_HOMEPAGE);
    struct uinput_setup us;
    memset(&us, 0, sizeof us);
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor = 0x0001;
    us.id.product = 0x0002;
    snprintf(us.name, sizeof us.name, "OnnShell Keys");
    if (ioctl(u, UI_DEV_SETUP, &us) < 0 || ioctl(u, UI_DEV_CREATE) < 0) {
        out("err uinput keys %d", errno);
        close(u);
        return -1;
    }
    return u;
}

static void press_home(void) {
    struct input_event e[4];
    memset(e, 0, sizeof e);
    e[0].type = EV_KEY; e[0].code = KEY_HOMEPAGE; e[0].value = 1;
    e[1].type = EV_SYN; e[1].code = SYN_REPORT;
    e[2].type = EV_KEY; e[2].code = KEY_HOMEPAGE; e[2].value = 0;
    e[3].type = EV_SYN; e[3].code = SYN_REPORT;
    if (keys >= 0 && write(keys, e, sizeof e) < 0) out("err home %d", errno);
    else out("home");
}

/* Lift every finger still down on the virtual screen (mode change, pad lost). */
static void lift_all(void) {
    int any = 0;
    for (int s = 0; s < SLOTS; s++) {
        if (!active[s]) continue;
        emit(EV_ABS, ABS_MT_SLOT, s);
        emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
        active[s] = 0;
        any = 1;
    }
    if (any) {
        emit(EV_KEY, BTN_TOUCH, 0);
        emit(EV_SYN, SYN_REPORT, 0);
    }
    slot = 0;
}

static void set_mode(int touch) {
    if (touch && ui < 0) ui = make_touchscreen();
    if (touch && ui < 0) touch = 0; /* no virtual screen, stay on the stock pointer */
    if (!touch) {
        lift_all();
        /* No virtual touchscreen outside TOUCH mode: a spare touchscreen can make Android hide the
         * touchpad pointer or treat the next input as touch. It is recreated on the next TOUCH. */
        if (ui >= 0) { close(ui); ui = -1; }
    }
    touch_mode = touch;
    if (pad >= 0 && touch != grabbed) { /* grabbing twice or releasing twice is EINVAL */
        if (ioctl(pad, EVIOCGRAB, (void *)(long)touch) == 0) grabbed = touch;
        else out("err grab %d", errno);
    }
}

static void scan(void) {
    char path[64];
    for (int i = 0; i < 64 && (pad < 0 || joy < 0); i++) {
        snprintf(path, sizeof path, "/dev/input/event%d", i);
        int f = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (f < 0) continue;
        struct input_id id;
        if (ioctl(f, EVIOCGID, &id) == 0 && id.vendor == 0x054c) {
            if (pad < 0 && has(f, EV_ABS, ABS_MT_POSITION_X)) {
                pad = f;
                snprintf(pad_path, sizeof pad_path, "%s", path);
                continue;
            }
            if (joy < 0 && has(f, EV_KEY, BTN_SELECT) && !has(f, EV_ABS, ABS_MT_POSITION_X)) {
                joy = f;
                joy_mac[0] = 0;
                ioctl(f, EVIOCGUNIQ(sizeof joy_mac), joy_mac); /* Bluetooth pads: their address */
                last_input = now_us(); idle_sent = 0;
                continue;
            }
        }
        close(f);
    }
    if (pad >= 0 && !announced) {
        int clk = CLOCK_MONOTONIC; /* event times on our clock, for the latency figure */
        ioctl(pad, EVIOCSCLOCKID, &clk);
        ioctl(pad, EVIOCGABS(ABS_MT_POSITION_X), &ax);
        ioctl(pad, EVIOCGABS(ABS_MT_POSITION_Y), &ay);
        announced = 1;
        out("pad %s %d %d", pad_path, ax.maximum - ax.minimum + 1, ay.maximum - ay.minimum + 1);
        if (touch_mode) set_mode(1);
    }
}

static int map(int v, struct input_absinfo *a, int origin, int size, int limit) {
    long range = (long)a->maximum - a->minimum;
    if (range <= 0) range = 1;
    long m = origin + ((long)(v - a->minimum) * (size - 1)) / range;
    if (m < 0) m = 0;
    if (m > limit - 1) m = limit - 1;
    return (int)m;
}

static void pad_event(struct input_event *e) {
    if (e->type == EV_KEY && e->code == BTN_LEFT && e->value == 1 && share) {
        set_mode(!touch_mode);
        out("toggle %s", mode_name());
        return;
    }
    if (!touch_mode || ui < 0) return;
    if (e->type == EV_ABS) {
        switch (e->code) {
        case ABS_MT_SLOT:
            slot = e->value;
            emit(EV_ABS, ABS_MT_SLOT, e->value);
            break;
        case ABS_MT_TRACKING_ID:
            if (slot >= 0 && slot < SLOTS) active[slot] = e->value >= 0;
            emit(EV_ABS, ABS_MT_TRACKING_ID, e->value);
            break;
        case ABS_MT_POSITION_X:
            emit(EV_ABS, ABS_MT_POSITION_X, map(e->value, &ax, rx, rw, scr_w));
            break;
        case ABS_MT_POSITION_Y:
            emit(EV_ABS, ABS_MT_POSITION_Y, map(e->value, &ay, ry, rh, scr_h));
            break;
        }
    } else if (e->type == EV_KEY && e->code == BTN_TOUCH) {
        emit(EV_KEY, BTN_TOUCH, e->value);
    } else if (e->type == EV_SYN && e->code == SYN_REPORT) {
        emit(EV_SYN, SYN_REPORT, 0);
        long long l = now_us() - frame_us; /* evdev timestamp of this frame to our write */
        if (l >= 0 && l < 1000000) {
            lat_sum += l;
            if (l > lat_max) lat_max = l;
            lat_n++;
        }
    }
}

static void drop(int *fd) {
    if (*fd == pad) {
        lift_all();
        out("nopad");
        announced = 0;
        grabbed = 0;
    } else {
        share = 0;
    }
    close(*fd);
    *fd = -1;
}

/* Real input, not noise: a resting DS4 stick flickers by one or two steps all the time (125/126 seen). */
static int moved(int fd, struct input_event *e) {
    if (e->type == EV_KEY) return 1;
    if (e->type != EV_ABS) return 0;
    if (fd != joy) return 1; /* the touchpad only reports a finger on it */
    switch (e->code) {
    case ABS_X: case ABS_Y: case ABS_RX: case ABS_RY: return abs(e->value - 128) > 40;
    case ABS_Z: case ABS_RZ: return e->value > 40; /* triggers rest at 0 */
    default: return e->value != 0; /* d-pad */
    }
}

static void read_dev(int *fd) {
    struct input_event ev[64];
    for (;;) {
        ssize_t n = read(*fd, ev, sizeof ev);
        if (n < 0 && errno == EAGAIN) return;
        if (n <= 0) { drop(fd); return; } /* unplugged or asleep: rescan later */
        for (ssize_t i = 0; i < n / (ssize_t)sizeof ev[0]; i++) {
            if (moved(*fd, &ev[i])) { last_input = now_us(); idle_sent = 0; }
            if (*fd == joy) {
                if (ev[i].type == EV_KEY && ev[i].code == BTN_SELECT) share = ev[i].value != 0;
                if (ev[i].type == EV_KEY && ev[i].code == BTN_MODE && ev[i].value == 1) press_home();
            } else {
                if (ev[i].type == EV_SYN) frame_us = ev[i].input_event_sec * 1000000LL + ev[i].input_event_usec;
                pad_event(&ev[i]);
            }
        }
    }
}

/* Returns 0 when stdin closed or "quit" arrived. */
static int read_cmds(void) {
    static char buf[256];
    static size_t len;
    ssize_t n = read(0, buf + len, sizeof buf - 1 - len);
    if (n <= 0) return 0;
    len += n;
    buf[len] = 0;
    char *line;
    while ((line = strchr(buf, '\n'))) {
        *line = 0;
        int a, b, c, d;
        if (!strcmp(buf, "mode touch")) { set_mode(1); out("mode %s", mode_name()); }
        else if (!strcmp(buf, "mode pointer")) { set_mode(0); out("mode %s", mode_name()); }
        else if (sscanf(buf, "screen %d %d", &a, &b) == 2 && a > 0 && b > 0) {
            if (a != scr_w || b != scr_h) {
                scr_w = a; scr_h = b;
                rx = 0; ry = 0; rw = a; rh = b;
                if (ui >= 0) { lift_all(); close(ui); ui = -1; }
                if (touch_mode) set_mode(1);
            }
        } else if (sscanf(buf, "rect %d %d %d %d", &a, &b, &c, &d) == 4 &&
                   a >= 0 && b >= 0 && c > 0 && d > 0 && a + c <= scr_w && b + d <= scr_h) {
            rx = a; ry = b; rw = c; rh = d;
        } else if (sscanf(buf, "idle %d", &a) == 1 && a > 0) {
            idle_us = a * 1000000LL;
            idle_sent = 0;
        } else if (!strcmp(buf, "home")) { /* same as a PS press, for tests over ADB */
            press_home();
        } else if (!strcmp(buf, "quit")) {
            return 0;
        } else if (buf[0]) {
            out("err unknown %s", buf);
        }
        size_t used = line - buf + 1;
        memmove(buf, line + 1, len - used + 1);
        len -= used;
    }
    if (len == sizeof buf - 1) len = 0; /* overlong garbage line */
    return 1;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    memset(&ax, 0, sizeof ax);
    memset(&ay, 0, sizeof ay);
    keys = make_keys();
    out("ready");
    scan();
    if (pad < 0) out("nopad");
    long long last_report = now_us(), last_scan = now_us();
    for (;;) {
        struct pollfd p[3];
        int n = 0, ip = -1, ij = -1;
        p[n].fd = 0; p[n].events = POLLIN; n++;
        if (pad >= 0) { ip = n; p[n].fd = pad; p[n].events = POLLIN; n++; }
        if (joy >= 0) { ij = n; p[n].fd = joy; p[n].events = POLLIN; n++; }
        int r = poll(p, n, (pad < 0 || joy < 0) ? 1000 : 2000);
        if (r < 0 && errno != EINTR) break;
        if (p[0].revents && !read_cmds()) break;
        if (ip >= 0 && p[ip].revents) read_dev(&pad);
        if (ij >= 0 && p[ij].revents) read_dev(&joy);
        /* hotplug: the DS4 sleeps and comes back. At most once a second, never per event. */
        if ((pad < 0 || joy < 0) && now_us() - last_scan > 1000000) {
            scan();
            last_scan = now_us();
        }
        if (joy >= 0 && !idle_sent && joy_mac[0] && now_us() - last_input > idle_us) {
            out("idle %s", joy_mac);
            idle_sent = 1;
        }
        if (lat_n && now_us() - last_report > 2000000) {
            out("lat %lld %lld %d", lat_sum / lat_n, lat_max, lat_n);
            lat_sum = lat_max = 0;
            lat_n = 0;
            last_report = now_us();
        }
    }
    set_mode(0);
    return 0;
}
