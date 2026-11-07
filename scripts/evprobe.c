/* Phase 0 evdev probe. Finds the DS4 touchpad, checks it is readable by the
 * shell user, and tests EVIOCGRAB. Read only: the grab is released at once.
 * Usage: onnshell-evprobe [/dev/input/eventN]   (no arg = scan for VID 054c)
 * Build: zig cc -target aarch64-linux-musl -static -Os -s -o onnshell-evprobe evprobe.c
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>

#define LBITS (8 * sizeof(long))
#define NLONGS(n) (((n) + LBITS - 1) / LBITS)
static int bit(const unsigned long *a, int b) { return (a[b / LBITS] >> (b % LBITS)) & 1; }

static void absinfo(int fd, int code, const char *label) {
    struct input_absinfo ai;
    if (ioctl(fd, EVIOCGABS(code), &ai) == 0)
        printf("%s=%d..%d fuzz=%d res=%d\n", label, ai.minimum, ai.maximum, ai.fuzz, ai.resolution);
    else
        printf("%s=absent\n", label);
}

/* Returns 1 if fd is a Sony device with multitouch X (the touchpad). */
static int is_touchpad(int fd) {
    struct input_id id;
    unsigned long abs[NLONGS(ABS_CNT)] = {0};
    if (ioctl(fd, EVIOCGID, &id) < 0 || id.vendor != 0x054c) return 0;
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof abs), abs);
    return bit(abs, ABS_MT_POSITION_X);
}

int main(int argc, char **argv) {
    char path[64] = "";
    int fd = -1;

    if (argc > 1) {
        snprintf(path, sizeof path, "%s", argv[1]);
        fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) { printf("node=%s\nopen=failed errno=%d (%s)\n", path, errno, strerror(errno)); return 1; }
    } else {
        for (int i = 0; i < 64 && fd < 0; i++) {
            snprintf(path, sizeof path, "/dev/input/event%d", i);
            int f = open(path, O_RDONLY | O_NONBLOCK);
            if (f < 0) {
                if (errno != ENOENT) printf("scan_open_failed=%s errno=%d (%s)\n", path, errno, strerror(errno));
                continue;
            }
            if (is_touchpad(f)) fd = f; else close(f);
        }
        if (fd < 0) { printf("touchpad=not_found\n"); return 2; }
    }

    char name[256] = "";
    struct input_id id = {0};
    unsigned long key[NLONGS(KEY_CNT)] = {0};
    ioctl(fd, EVIOCGNAME(sizeof name), name);
    ioctl(fd, EVIOCGID, &id);
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof key), key);

    printf("touchpad=found\nnode=%s\nname=%s\n", path, name);
    printf("bus=%04x vendor=%04x product=%04x version=%04x\n", id.bustype, id.vendor, id.product, id.version);
    printf("open=ok (shell uid %d)\n", (int)getuid());

    /* Non-blocking read: EAGAIN means the node is readable but idle. */
    struct input_event ev;
    ssize_t n = read(fd, &ev, sizeof ev);
    if (n >= 0 || errno == EAGAIN) printf("read=ok\n");
    else printf("read=failed errno=%d (%s)\n", errno, strerror(errno));

    printf("btn_left=%s\nbtn_touch=%s\n", bit(key, BTN_LEFT) ? "yes" : "no", bit(key, BTN_TOUCH) ? "yes" : "no");
    absinfo(fd, ABS_MT_SLOT, "abs_mt_slot");
    absinfo(fd, ABS_MT_POSITION_X, "abs_mt_x");
    absinfo(fd, ABS_MT_POSITION_Y, "abs_mt_y");
    absinfo(fd, ABS_MT_TRACKING_ID, "abs_mt_tracking_id");
    absinfo(fd, ABS_X, "abs_x");
    absinfo(fd, ABS_Y, "abs_y");

    if (ioctl(fd, EVIOCGRAB, (void *)1) != 0) {
        printf("grab=failed errno=%d (%s)\n", errno, strerror(errno));
        close(fd);
        return 0;
    }
    printf("grab=ok\n");

    /* A second open trying to grab must get EBUSY if the grab is really exclusive. */
    int fd2 = open(path, O_RDONLY | O_NONBLOCK);
    if (fd2 >= 0) {
        int r = ioctl(fd2, EVIOCGRAB, (void *)1);
        if (r != 0 && errno == EBUSY) printf("grab_exclusive=yes\n");
        else { printf("grab_exclusive=no r=%d errno=%d\n", r, errno); if (r == 0) ioctl(fd2, EVIOCGRAB, (void *)0); }
        close(fd2);
    }

    printf("release=%s\n", ioctl(fd, EVIOCGRAB, (void *)0) == 0 ? "ok" : "failed");
    close(fd);
    return 0;
}
