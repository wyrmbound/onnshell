/* BufferBridge JNI: HardwareBuffer -> dma-buf fd + stride, sent to the 64-bit renderer over an abstract
 * unix socket with SCM_RIGHTS. Runs inside the Shizuku user service (shell uid), so the socket is
 * shell -> shell. AHardwareBuffer_getNativeHandle is LLNDK (not in the NDK stubs) but exported by the
 * real libnativewindow.so, hence dlsym. On Arm gralloc the handle's first fd is the dma-buf. */
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "M4", __VA_ARGS__)

typedef struct { int version; int numFds; int numInts; int data[0]; } native_handle_t; /* cutils layout */
typedef const native_handle_t *(*get_nh_fn)(const AHardwareBuffer *);

JNIEXPORT jint JNICALL
Java_com_xplsh_onnshell_display_BufferBridge_send(JNIEnv *env, jclass cls, jobjectArray bufs, jstring jsock)
{
    void *nw = dlopen("libnativewindow.so", RTLD_NOW);
    get_nh_fn get_nh = nw ? (get_nh_fn)dlsym(nw, "AHardwareBuffer_getNativeHandle") : NULL;
    if (!get_nh) { LOG("AHardwareBuffer_getNativeHandle not found: %s", dlerror()); return -ENOSYS; }

    int count = (*env)->GetArrayLength(env, bufs);
    if (count < 1 || count > 4) return -EINVAL;
    int fds[4]; uint32_t strides[4], w = 0, h = 0;
    for (int i = 0; i < count; i++) {
        jobject jb = (*env)->GetObjectArrayElement(env, bufs, i);
        AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, jb);
        if (!ahb) return -EINVAL;
        AHardwareBuffer_Desc d; AHardwareBuffer_describe(ahb, &d);
        const native_handle_t *nh = get_nh(ahb);
        if (!nh || nh->numFds < 1) { LOG("buffer %d: no native handle / no fds", i); return -EBADF; }
        fds[i] = nh->data[0]; strides[i] = d.stride * 4; w = d.width; h = d.height;
        char link[64] = "?", path[64]; snprintf(path, sizeof path, "/proc/self/fd/%d", fds[i]);
        ssize_t n = readlink(path, link, sizeof link - 1); if (n > 0) link[n] = 0;
        LOG("buffer %d: %ux%u fmt %u stride %u px, handle numFds %d numInts %d, fd %d -> %s",
            i, d.width, d.height, d.format, d.stride, nh->numFds, nh->numInts, fds[i], link);
    }

    const char *name = (*env)->GetStringUTFChars(env, jsock, NULL);
    struct sockaddr_un sa = { .sun_family = AF_UNIX };
    size_t nl = strlen(name); if (nl > sizeof sa.sun_path - 2) return -ENAMETOOLONG;
    memcpy(sa.sun_path + 1, name, nl);
    socklen_t sl = offsetof(struct sockaddr_un, sun_path) + 1 + nl;
    (*env)->ReleaseStringUTFChars(env, jsock, name);
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0); if (s < 0) return -errno;
    int rc = -1;
    for (int tries = 0; tries < 300 && rc < 0; tries++) /* 30 s: a game needs a few seconds of GL init before it listens */ { rc = connect(s, (struct sockaddr *)&sa, sl); if (rc < 0) usleep(100000); }
    if (rc < 0) { int e = errno; LOG("connect @sock: %s", strerror(e)); close(s); return -e; }

    uint32_t hdr[5] = { 0x4642344d, (uint32_t)count, w, h, 1 };
    char cbuf[CMSG_SPACE(sizeof(int) * 4)];
    struct iovec iov = { hdr, sizeof hdr };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = cbuf, .msg_controllen = CMSG_SPACE(sizeof(int) * count) };
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int) * count);
    memcpy(CMSG_DATA(c), fds, sizeof(int) * count);
    if (sendmsg(s, &msg, 0) != (ssize_t)sizeof hdr) { int e = errno; close(s); return -e; }
    if (write(s, strides, sizeof(uint32_t) * count) != (ssize_t)(sizeof(uint32_t) * count)) { int e = errno; close(s); return -e; }
    close(s);
    LOG("sent %d buffers to the renderer", count);
    return 0;
}
