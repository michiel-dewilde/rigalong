#include "RealtimePriority.h"

#include <android/log.h>
#include <dlfcn.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <atomic>
#include <string>

#define LOG_TAG "bos-sched"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace bos {

void raiseRenderPriority(const char* what, int niceValue) {
    // 1. Real-time, properly. Priority 2 rather than something greedy: this is
    //    the band AudioFlinger's own fast mixer uses, and a thread ABOVE the
    //    audio HAL is a thread that can starve it.
    //
    //    The transport keeps 2; anything that merely feeds it drops to 1, so
    //    the ordering survives if a build ever does grant SCHED_FIFO.
    sched_param p{};
    p.sched_priority = niceValue <= kTransportNice ? 2 : 1;
    if (sched_setscheduler(0, SCHED_FIFO, &p) == 0) {
        LOGI("%s: SCHED_FIFO priority %d", what, p.sched_priority);
        return;
    }
    const int fifoErr = errno;

    // 2. What Android actually grants an app: ANDROID_PRIORITY_URGENT_AUDIO.
    errno = 0;
    if (setpriority(PRIO_PROCESS, 0, niceValue) == 0) {
        LOGI("%s: nice %d (SCHED_FIFO refused: %s)", what, niceValue,
             std::strerror(fifoErr));
        return;
    }
    LOGE("%s: no priority raise -- SCHED_FIFO: %s, nice %d: %s", what,
         std::strerror(fifoErr), niceValue, std::strerror(errno));
}

// --------------------------------------------------------------------- ADPF
//
// Resolved with dlsym rather than called directly.
//
// The NDK marks these __INTRODUCED_IN(33) and this app's minSdk is 29, so a
// direct call is a compile error, and `__builtin_available` did not satisfy the
// toolchain here. dlsym sidesteps the question entirely and is honest about
// what it is doing: if the platform has the symbols, use them; if not, do
// nothing. It also survives a device that ships the header's API level without
// the implementation.
namespace {

// -1 means no pinning at all, which is the default and the shipped behaviour.
std::atomic<int> gTransportCore{-1};
std::atomic<bool> gPinTransportThread{false};

// The mask that was actually granted, read back rather than assumed: a
// setaffinity that succeeds can still be narrowed by the cpuset the app is in.
std::string allowedList() {
    cpu_set_t got;
    CPU_ZERO(&got);
    if (sched_getaffinity(0, sizeof(got), &got) != 0) return "unknown";
    std::string out;
    for (int i = 0; i < CPU_SETSIZE && i < 64; ++i) {
        if (!CPU_ISSET(i, &got)) continue;
        if (!out.empty()) out += ",";
        out += std::to_string(i);
    }
    return out.empty() ? "none" : out;
}

void applyAffinity(const char* what, bool onlyTransportCore) {
    const int core = gTransportCore.load(std::memory_order_relaxed);
    if (core < 0) return;                       // the default: do nothing
    const long cores = sysconf(_SC_NPROCESSORS_CONF);
    if (core >= cores) {
        LOGI("%s: transport core %d does not exist (%ld cores)", what, core, cores);
        return;
    }
    cpu_set_t set;
    CPU_ZERO(&set);
    if (onlyTransportCore) {
        CPU_SET(core, &set);
    } else {
        for (long i = 0; i < cores; ++i) {
            if (i != core) CPU_SET(static_cast<int>(i), &set);
        }
    }
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        LOGE("%s: affinity refused: %s", what, std::strerror(errno));
        return;
    }
    LOGI("%s: cpus %s", what, allowedList().c_str());
}

}  // namespace

void setTransportCore(int core) {
    gTransportCore.store(core, std::memory_order_relaxed);
    LOGI("transport core: %s", core < 0 ? "none (default)"
                                        : std::to_string(core).c_str());
}
int transportCore() { return gTransportCore.load(std::memory_order_relaxed); }

void setPinTransportThread(bool pin) {
    gPinTransportThread.store(pin, std::memory_order_relaxed);
}

void pinToTransportCore(const char* what) {
    // In reserve mode the reap thread is deliberately left alone: the core is
    // kept clear of our renderer, but this thread may still run anywhere.
    if (!gPinTransportThread.load(std::memory_order_relaxed)) {
        LOGI("%s: not pinned (core reserved, thread free to migrate)", what);
        return;
    }
    applyAffinity(what, true);
}
void pinOffTransportCore(const char* what) { applyAffinity(what, false); }

namespace {

struct Adpf {
    void* (*getManager)() = nullptr;
    void* (*createSession)(void*, const int32_t*, size_t, int64_t) = nullptr;
    void (*updateTarget)(void*, int64_t) = nullptr;
    void (*reportActual)(void*, int64_t) = nullptr;
    void (*closeSession)(void*) = nullptr;
    bool tried = false;
    bool ok = false;
};

Adpf& adpf() {
    static Adpf a;
    if (a.tried) return a;
    a.tried = true;

    // Already loaded into every app process; this just takes a handle to it.
    void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (lib == nullptr) lib = dlopen("libandroid.so", RTLD_NOW);
    if (lib == nullptr) {
        LOGI("performance hints: libandroid.so not available");
        return a;
    }
    a.getManager = reinterpret_cast<decltype(a.getManager)>(
        dlsym(lib, "APerformanceHint_getManager"));
    a.createSession = reinterpret_cast<decltype(a.createSession)>(
        dlsym(lib, "APerformanceHint_createSession"));
    a.updateTarget = reinterpret_cast<decltype(a.updateTarget)>(
        dlsym(lib, "APerformanceHint_updateTargetWorkDuration"));
    a.reportActual = reinterpret_cast<decltype(a.reportActual)>(
        dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
    a.closeSession = reinterpret_cast<decltype(a.closeSession)>(
        dlsym(lib, "APerformanceHint_closeSession"));

    a.ok = a.getManager && a.createSession && a.reportActual && a.closeSession;
    if (!a.ok) LOGI("performance hints: not on this platform (needs Android 13)");
    return a;
}

}  // namespace

PerfHint::~PerfHint() { stop(); }

void PerfHint::start(const std::vector<pid_t>& tids, int64_t targetNs) {
    stop();
    if (tids.empty() || targetNs <= 0) return;
    mTargetNs = targetNs;

    Adpf& a = adpf();
    if (!a.ok) return;

    void* mgr = a.getManager();
    if (mgr == nullptr) {
        LOGI("performance hints: no manager on this device");
        return;
    }
    std::vector<int32_t> ids(tids.begin(), tids.end());
    void* s = a.createSession(mgr, ids.data(), ids.size(), targetNs);
    if (s == nullptr) {
        // Not an error: plenty of devices simply do not implement it.
        LOGI("performance hints: session refused for %zu thread(s)", ids.size());
        return;
    }
    mSession = s;
    LOGI("performance hints: session for %zu thread(s), target %.3f ms",
         ids.size(), static_cast<double>(targetNs) / 1e6);
}

void PerfHint::setTarget(int64_t targetNs) {
    if (mSession == nullptr || targetNs <= 0 || targetNs == mTargetNs) return;
    Adpf& a = adpf();
    if (a.updateTarget != nullptr) {
        a.updateTarget(mSession, targetNs);
        mTargetNs = targetNs;
    }
}

void PerfHint::report(int64_t actualNs) {
    if (mSession == nullptr || actualNs <= 0) return;
    adpf().reportActual(mSession, actualNs);
}

void PerfHint::stop() {
    if (mSession == nullptr) return;
    adpf().closeSession(mSession);
    mSession = nullptr;
    LOGI("performance hints: session closed");
}

}  // namespace bos
