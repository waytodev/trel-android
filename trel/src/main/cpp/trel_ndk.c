#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <unwind.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>

static char g_path[512];
static struct sigaction g_old[32];
static int g_ready = 0;

struct Bt {
    char buf[48 * 1024];
    int used;
    int count;
};

static _Unwind_Reason_Code walk(struct _Unwind_Context *ctx, void *arg) {
    struct Bt *st = (struct Bt *)arg;
    if (st->count >= 64 || st->used > 40000) return _URC_END_OF_STACK;
    uintptr_t ip = _Unwind_GetIP(ctx);
    Dl_info info;
    const char *lib = "unknown";
    const char *sym = "";
    memset(&info, 0, sizeof(info));
    if (dladdr((void *)ip, &info) != 0 && info.dli_fname) {
        lib = info.dli_fname;
        if (info.dli_sname) sym = info.dli_sname;
    }
    int n = snprintf(
        st->buf + st->used,
        sizeof(st->buf) - (size_t)st->used,
        "#%02d pc %016lx %s (%s)\n",
        st->count,
        (unsigned long)ip,
        lib,
        sym);
    if (n > 0) st->used += n;
    st->count++;
    return _URC_NO_REASON;
}

static void handle(int sig, siginfo_t *info, void *ucontext) {
    if (g_ready) {
        struct Bt st;
        memset(&st, 0, sizeof(st));
        int n = snprintf(st.buf, sizeof(st.buf), "SIG %d at %p\n", sig, info ? info->si_addr : 0);
        if (n > 0) st.used = n;
        _Unwind_Backtrace(walk, &st);
        int fd = open(g_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            const char *p = st.buf;
            int left = st.used;
            while (left > 0) {
                int w = (int)write(fd, p, (size_t)left);
                if (w <= 0) break;
                p += w;
                left -= w;
            }
            close(fd);
        }
    }
    struct sigaction *old = (sig >= 0 && sig < 32) ? &g_old[sig] : 0;
    if (old && old->sa_sigaction && (old->sa_flags & SA_SIGINFO)) old->sa_sigaction(sig, info, ucontext);
    else if (old && old->sa_handler && old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN) old->sa_handler(sig);
    else signal(sig, SIG_DFL);
}

static void install_one(int sig) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = handle;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigaction(sig, &action, &g_old[sig]);
}

JNIEXPORT void JNICALL Java_to_trel_internal_Ndk_nativeInstall(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    const char *utf = (*env)->GetStringUTFChars(env, path, 0);
    if (!utf) return;
    snprintf(g_path, sizeof(g_path), "%s", utf);
    (*env)->ReleaseStringUTFChars(env, path, utf);
    install_one(SIGSEGV);
    install_one(SIGABRT);
    install_one(SIGBUS);
    install_one(SIGFPE);
    g_ready = 1;
}
