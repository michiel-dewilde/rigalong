#include <jni.h>
#include <atomic>
#include <android/log.h>

#include <algorithm>
#include <memory>
#include <string>
#include <vector>

#include "AudioEngine.h"
#include "usb/UsbControl.h"

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#include <bagosynths/UsbDescriptors.h>

namespace {
// One engine for the process lifetime. Never destroyed while the stream could be
// running; stop() is idempotent.
AudioEngine& engine() {
    static AudioEngine instance;
    return instance;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeStart(JNIEnv*, jobject, jint deviceId, jint bufferBursts) {
    return engine().start(deviceId, bufferBursts) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeStop(JNIEnv*, jobject) {
    engine().stop();
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeOpenMidi(JNIEnv* env, jobject, jobject midiDevice,
                                                 jint portIndex) {
    return engine().midi().open(env, midiDevice, portIndex) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeMidiOpen(JNIEnv*, jobject) {
    return engine().midi().isOpen() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeCloseMidi(JNIEnv*, jobject) {
    engine().midi().close();
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativePanic(JNIEnv*, jobject) {
    engine().allNotesOff();
}

// Called from Kotlin once RECORD_AUDIO has been granted. Optional: the
// instrument works fully without it, only the latency probe needs input.
JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeEnableInput(JNIEnv*, jobject, jint deviceId) {
    return engine().openInput(deviceId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeStartLatencyProbe(JNIEnv*, jobject) {
    engine().startLatencyProbe();
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeStressChord(JNIEnv*, jobject, jint count) {
    engine().stressChord(count);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeLogProfile(JNIEnv*, jobject) {
    engine().logProfile();
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeStartNoteProbe(JNIEnv*, jobject) {
    engine().startNoteProbe();
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeStartRecording(JNIEnv*, jobject, jint seconds) {
    return engine().startRecording(seconds) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeWriteRecording(JNIEnv* env, jobject, jstring wav,
                                                      jstring marks) {
    const char* w = env->GetStringUTFChars(wav, nullptr);
    const char* m = env->GetStringUTFChars(marks, nullptr);
    bool ok = false;
    if (w && m) ok = engine().writeRecording(std::string(w), std::string(m));
    if (w) env->ReleaseStringUTFChars(wav, w);
    if (m) env->ReleaseStringUTFChars(marks, m);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeRecordedSeconds(JNIEnv*, jobject) {
    return engine().recordedSeconds();
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeIsRecording(JNIEnv*, jobject) {
    return engine().isRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetRenderPaused(JNIEnv*, jobject, jboolean p) {
    engine().setRenderPaused(p == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetConcealment(JNIEnv*, jobject, jboolean on) {
    engine().setConcealment(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeInjectRawZero(JNIEnv*, jobject, jint count) {
    engine().injectUsbRawZero(count);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeInjectSize(JNIEnv*, jobject, jint count,
                                                    jint frames) {
    engine().injectUsbSize(count, frames);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeInjectBias(JNIEnv*, jobject, jint count,
                                                    jint delta) {
    engine().injectUsbBias(count, delta);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeInjectStarve(JNIEnv*, jobject, jint count) {
    engine().injectUsbStarve(count);
}

// THE FEEDBACK DEPTH, AND THE FIFO GEOMETRY BESIDE IT. All settable from a
// broadcast, because the two policies below differ in five numbers and an A/B
// that needs a rebuild between arms is an A/B nobody runs twice.
//
//   resync (default):  in 24 ms, fifo 24/20, a full FIFO forces a gap
//                      -- latched 0 of 10
//   trim (section 36): in 1024 ms, fifo 48/24, a full FIFO is trimmed
//                      -- latched 4 of 7, and 5 of 6 at the SAME 12/8 geometry
std::atomic<int> gInDepthMs{24};
std::atomic<int> gInPktsPerUrb{1};
std::atomic<int> gFifoLen{24};
std::atomic<int> gFifoPreRoll{20};
std::atomic<int> gFifoResync{1};
// Packets per OUT URB. Latency is urbs x packets x period and is set by
// `buffer`; this decides how that depth is DIVIDED, which is the slack --
// (urbs - 1) x packets -- and is a variable in its own right.
std::atomic<int> gOutPktsPerUrb{1};
// MIDI IN URBs, each one wMaxPacketSize. 0 falls back to the synchronous
// bulk ioctl, which has no queue at all and is what shipped until now.
std::atomic<int> gMidiUrbs{4};
// Microseconds of silence on a resync. Section 34 measured 2 ms as enough
// to make the device discard its accumulated deviation.
std::atomic<int> gResyncGapUs{4000};

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetInDepthMs(JNIEnv*, jobject, jint ms) {
    gInDepthMs.store(ms > 0 ? ms : 24, std::memory_order_relaxed);
}

// The whole size-FIFO geometry in one call, so the resync and trim policies can
// be swapped from a broadcast. -1 leaves a field alone.
JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetFifo(JNIEnv*, jobject, jint len,
                                                  jint preRoll, jint resync,
                                                  jint inPkts, jint outPkts,
                                                  jint midiUrbs, jint gapUs) {
    if (midiUrbs >= 0) gMidiUrbs.store(midiUrbs, std::memory_order_relaxed);
    if (gapUs >= 0) gResyncGapUs.store(gapUs > 0 ? gapUs : 1, std::memory_order_relaxed);
    if (outPkts > 0) gOutPktsPerUrb.store(outPkts, std::memory_order_relaxed);
    if (len > 0) gFifoLen.store(len, std::memory_order_relaxed);
    if (preRoll > 0) gFifoPreRoll.store(preRoll, std::memory_order_relaxed);
    if (resync >= 0) gFifoResync.store(resync, std::memory_order_relaxed);
    if (inPkts > 0) gInPktsPerUrb.store(inPkts, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetUsbTone(JNIEnv*, jobject, jfloat hz) {
    engine().setUsbTone(hz);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeDumpUsbTrace(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p != nullptr) {
        engine().dumpUsbTrace(p);
        env->ReleaseStringUTFChars(path, p);
    }
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeHoldOut(JNIEnv*, jobject, jint us,
                                                  jint count, jint every) {
    engine().holdUsbOut(us, count, every);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeResetPeaks(JNIEnv*, jobject) {
    engine().resetPeaks();
}

// Plays a note without MIDI, so the audio path can be verified before the piano
// is even plugged in.
JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeTestNote(JNIEnv*, jobject, jint note, jint velocity) {
    engine().injectNote(note, velocity);
}

// Loads an SFZ instrument on the loader thread. Returns immediately.
JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeLoadInstrument(JNIEnv* env, jobject, jstring id,
                                                       jstring path) {
    const char* idChars = env->GetStringUTFChars(id, nullptr);
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    if (idChars != nullptr && pathChars != nullptr) {
        engine().loadInstrumentAsync(std::string(idChars), std::string(pathChars));
    }
    if (idChars) env->ReleaseStringUTFChars(id, idChars);
    if (pathChars) env->ReleaseStringUTFChars(path, pathChars);
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeLoadedInstrument(JNIEnv* env, jobject) {
    return env->NewStringUTF(engine().loadedInstrument().c_str());
}

// --------------------------------------------------------------- the organ
//
// Parameters are addressed by index into HammondNode::Param, in natural units:
// drawbars 0..8, switches 0/1, the Leslie 0/1/2, everything else 0..1. No
// normalisation layer, because there is nothing between the UI and the node
// that would benefit from one.

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetOrganParam(JNIEnv*, jobject, jint index,
                                                      jfloat value) {
    if (auto* h = engine().hammond()) h->setParam(index, value);
}

JNIEXPORT jfloat JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeGetOrganParam(JNIEnv*, jobject, jint index) {
    auto* h = engine().hammond();
    return h ? h->getParam(index) : 0.0f;
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeOrganParamName(JNIEnv* env, jobject, jint index) {
    auto* h = engine().hammond();
    return env->NewStringUTF(h ? h->paramName(index) : "");
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeOrganPreset(JNIEnv*, jobject, jint index,
                                                    jint manual) {
    if (auto* h = engine().hammond()) h->applyPreset(index, manual);
}

JNIEXPORT jobjectArray JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeOrganPresetNames(JNIEnv* env, jobject) {
    const int count = bagosynths::HammondNode::presetCount();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(count, stringClass, nullptr);
    for (int i = 0; i < count; ++i) {
        jstring name = env->NewStringUTF(bagosynths::HammondNode::presetName(i));
        env->SetObjectArrayElement(out, i, name);
        env->DeleteLocalRef(name);
    }
    return out;
}

// The sampler's level, so the two parts can be balanced against each other.
JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetSamplerLevel(JNIEnv*, jobject, jfloat value) {
    if (auto* s = engine().sfizz()) s->setParam(bagosynths::SfizzNode::kLevel, value);
}

JNIEXPORT jfloat JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeGetSamplerLevel(JNIEnv*, jobject) {
    auto* s = engine().sfizz();
    return s ? s->getParam(bagosynths::SfizzNode::kLevel) : 0.0f;
}

// ---------------------------------------------------------------- surge xt
//
// Deliberately not a synth editor: Surge has around 2000 parameters, and a UI
// for a synth that deep is its own project. What crosses this boundary is the
// patch library, the eight macros the patch author chose, and a level.

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeInitSurge(JNIEnv* env, jobject, jstring dataPath,
                                                  jstring homePath) {
    const char* d = env->GetStringUTFChars(dataPath, nullptr);
    const char* h = env->GetStringUTFChars(homePath, nullptr);
    engine().initSurgeAsync(d ? d : "", h ? h : "");
    if (d) env->ReleaseStringUTFChars(dataPath, d);
    if (h) env->ReleaseStringUTFChars(homePath, h);
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSurgeReady(JNIEnv*, jobject) {
    auto* s = engine().surge();
    return s && s->ready() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSurgeBusy(JNIEnv*, jobject) {
    return engine().isSurgeBusy() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSurgePatchCount(JNIEnv*, jobject) {
    auto* s = engine().surge();
    return s ? s->patchCount() : 0;
}

// "Category/Name" -- the stable address a saved patch stores. An index would
// mean something different the moment the library changes underneath it.
JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSurgePatchName(JNIEnv* env, jobject, jint index) {
    auto* s = engine().surge();
    return env->NewStringUTF(s ? s->patchName(index).c_str() : "");
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSurgePatchShortName(JNIEnv* env, jobject, jint index) {
    auto* s = engine().surge();
    return env->NewStringUTF(s ? s->patchDisplayName(index).c_str() : "");
}

JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSurgeCurrentPatch(JNIEnv*, jobject) {
    auto* s = engine().surge();
    return s ? s->currentPatch() : -1;
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSurgeCurrentPatchName(JNIEnv* env, jobject) {
    auto* s = engine().surge();
    return env->NewStringUTF(s ? s->currentPatchName().c_str() : "");
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSurgeLoadPatch(JNIEnv*, jobject, jint index) {
    engine().loadSurgePatchAsync(index);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSurgeLoadPatchNamed(JNIEnv* env, jobject, jstring name) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    if (n) {
        engine().loadSurgePatchNamedAsync(n);
        env->ReleaseStringUTFChars(name, n);
    }
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSurgeMacroName(JNIEnv* env, jobject, jint index) {
    auto* s = engine().surge();
    return env->NewStringUTF(s ? s->macroName(index).c_str() : "");
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetSurgeParam(JNIEnv*, jobject, jint index,
                                                      jfloat value) {
    if (auto* s = engine().surge()) s->setParam(index, value);
}

JNIEXPORT jfloat JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeGetSurgeParam(JNIEnv*, jobject, jint index) {
    auto* s = engine().surge();
    return s ? s->getParam(index) : 0.0f;
}

// ------------------------------------------------------------------- dexed
//
// A DX7 voice is addressed by "Bank/NAME", for the same reason a Surge patch and
// an instrument are: an index means something different the moment the library
// changes underneath it.

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeLoadDexedBanks(JNIEnv* env, jobject,
                                                       jobjectArray dirs) {
    std::vector<std::string> paths;
    const jsize n = dirs != nullptr ? env->GetArrayLength(dirs) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(dirs, i));
        if (s == nullptr) continue;
        if (const char* c = env->GetStringUTFChars(s, nullptr)) {
            paths.emplace_back(c);
            env->ReleaseStringUTFChars(s, c);
        }
        env->DeleteLocalRef(s);
    }
    engine().loadDexedBanksAsync(paths);
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeDexedReady(JNIEnv*, jobject) {
    auto* d = engine().dexed();
    return d && d->ready() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeDexedVoiceCount(JNIEnv*, jobject) {
    auto* d = engine().dexed();
    return d ? d->voiceCount() : 0;
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeDexedVoiceName(JNIEnv* env, jobject, jint index) {
    auto* d = engine().dexed();
    return env->NewStringUTF(d ? d->voiceName(index).c_str() : "");
}

JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeDexedCurrentVoice(JNIEnv*, jobject) {
    auto* d = engine().dexed();
    return d ? d->currentVoice() : -1;
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeDexedCurrentVoiceName(JNIEnv* env, jobject) {
    auto* d = engine().dexed();
    return env->NewStringUTF(d ? d->currentVoiceName().c_str() : "");
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeDexedSelectVoice(JNIEnv*, jobject, jint index) {
    if (auto* d = engine().dexed()) d->selectVoice(index);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeDexedSelectVoiceNamed(JNIEnv* env, jobject,
                                                              jstring name) {
    auto* d = engine().dexed();
    if (d == nullptr) return;
    if (const char* n = env->GetStringUTFChars(name, nullptr)) {
        d->selectVoiceNamed(n);
        env->ReleaseStringUTFChars(name, n);
    }
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetDexedParam(JNIEnv*, jobject, jint index,
                                                      jfloat value) {
    if (auto* d = engine().dexed()) d->setParam(index, value);
}

JNIEXPORT jfloat JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeGetDexedParam(JNIEnv*, jobject, jint index) {
    auto* d = engine().dexed();
    return d ? d->getParam(index) : 0.0f;
}

JNIEXPORT jobjectArray JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeDexedEngineNames(JNIEnv* env, jobject) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(bagosynths::DexedNode::kNumEngines, stringClass, nullptr);
    for (int i = 0; i < bagosynths::DexedNode::kNumEngines; ++i) {
        jstring name = env->NewStringUTF(bagosynths::DexedNode::engineName(i));
        env->SetObjectArrayElement(out, i, name);
        env->DeleteLocalRef(name);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetPlaitsParam(JNIEnv*, jobject, jint index,
                                                       jfloat value) {
    if (auto* p = engine().plaits()) p->setParam(index, value);
}

JNIEXPORT jfloat JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeGetPlaitsParam(JNIEnv*, jobject, jint index) {
    auto* p = engine().plaits();
    return p ? p->getParam(index) : 0.0f;
}

JNIEXPORT jobjectArray JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativePlaitsEngineNames(JNIEnv* env, jobject) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(bagosynths::PlaitsNode::kNumEngines, stringClass, nullptr);
    for (int i = 0; i < bagosynths::PlaitsNode::kNumEngines; ++i) {
        jstring name = env->NewStringUTF(bagosynths::PlaitsNode::engineName(i));
        env->SetObjectArrayElement(out, i, name);
        env->DeleteLocalRef(name);
    }
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativePlaitsParamLabels(JNIEnv* env, jobject, jint engine) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out =
            env->NewObjectArray(bagosynths::PlaitsNode::kNumLabelled, stringClass, nullptr);
    for (int i = 0; i < bagosynths::PlaitsNode::kNumLabelled; ++i) {
        jstring name = env->NewStringUTF(bagosynths::PlaitsNode::paramLabel(engine, i));
        env->SetObjectArrayElement(out, i, name);
        env->DeleteLocalRef(name);
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativePlaitsActiveVoices(JNIEnv*, jobject) {
    auto* p = engine().plaits();
    return p ? p->activeVoices() : 0;
}

// ------------------------------------------------------------- fluidsynth
//
// A SoundFont is content, like an SFZ instrument: it lives outside the APK and
// is addressed by NAME, never by index. Two names matter -- the font, and the
// preset within it as "bank:prog NAME" -- and both are what a saved patch
// stores.

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeInitFluid(JNIEnv* env, jobject, jobjectArray dirs) {
    std::vector<std::string> paths;
    const jsize n = env->GetArrayLength(dirs);
    for (jsize i = 0; i < n; ++i) {
        auto s = reinterpret_cast<jstring>(env->GetObjectArrayElement(dirs, i));
        const char* c = env->GetStringUTFChars(s, nullptr);
        if (c != nullptr) paths.emplace_back(c);
        if (c) env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    engine().initFluidAsync(paths);
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidReady(JNIEnv*, jobject) {
    auto* f = engine().fluid();
    return f && f->ready() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidBusy(JNIEnv*, jobject) {
    return engine().isFluidBusy() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidFontCount(JNIEnv*, jobject) {
    auto* f = engine().fluid();
    return f ? f->soundFontCount() : 0;
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidFontName(JNIEnv* env, jobject, jint index) {
    auto* f = engine().fluid();
    return env->NewStringUTF(f ? f->soundFontName(index).c_str() : "");
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidCurrentFont(JNIEnv* env, jobject) {
    auto* f = engine().fluid();
    return env->NewStringUTF(f ? f->currentSoundFontName().c_str() : "");
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidLoadFont(JNIEnv*, jobject, jint index) {
    engine().loadSoundFontAsync(index);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidLoadFontNamed(JNIEnv* env, jobject, jstring name) {
    const char* c = env->GetStringUTFChars(name, nullptr);
    if (c != nullptr) engine().loadSoundFontNamedAsync(std::string(c));
    if (c) env->ReleaseStringUTFChars(name, c);
}

JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidPresetCount(JNIEnv*, jobject) {
    auto* f = engine().fluid();
    return f ? f->presetCount() : 0;
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidPresetName(JNIEnv* env, jobject, jint index) {
    auto* f = engine().fluid();
    return env->NewStringUTF(f ? f->presetName(index).c_str() : "");
}

JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidCurrentPreset(JNIEnv*, jobject) {
    auto* f = engine().fluid();
    return f ? f->currentPreset() : -1;
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidCurrentPresetName(JNIEnv* env, jobject) {
    auto* f = engine().fluid();
    return env->NewStringUTF(f ? f->currentPresetName().c_str() : "");
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidSelectPreset(JNIEnv*, jobject, jint index) {
    if (auto* f = engine().fluid()) f->selectPreset(index);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeFluidSelectPresetNamed(JNIEnv* env, jobject,
                                                               jstring name) {
    auto* f = engine().fluid();
    const char* c = env->GetStringUTFChars(name, nullptr);
    if (f != nullptr && c != nullptr) {
        // Same rule as Surge and Dexed: record the intent whether or not the
        // library is up yet, so a restore that arrives first is still honoured.
        f->requestPresetNamed(std::string(c));
        f->selectPresetNamed(std::string(c));
    }
    if (c) env->ReleaseStringUTFChars(name, c);
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetFluidParam(JNIEnv*, jobject, jint index,
                                                      jfloat value) {
    if (auto* f = engine().fluid()) f->setParam(index, value);
}

JNIEXPORT jfloat JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeGetFluidParam(JNIEnv*, jobject, jint index) {
    auto* f = engine().fluid();
    return f ? f->getParam(index) : 0.0f;
}

// ------------------------------------------------------- the graph, generically
//
// Enough to walk the graph without knowing what is in it: node types, their
// parameters by name, and the edges. Saving a patch needs exactly this, and so
// will the editor -- which is why it is a general surface rather than a
// save-the-organ function.
//
// Parameters are addressed by NAME in the file and by index here. That is
// deliberate: an index is fast and an index is also what changes when someone
// inserts an enum value, so the file must not contain one.

JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeNodeCount(JNIEnv*, jobject) {
    return engine().graph().nodeCount();
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeNodeType(JNIEnv* env, jobject, jint index) {
    bagosynths::Node* n = engine().graph().nodeAt(index);
    return env->NewStringUTF(n ? n->typeName() : "");
}

JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeNodeParamCount(JNIEnv*, jobject, jint index) {
    bagosynths::Node* n = engine().graph().nodeAt(index);
    return n ? n->numParams() : 0;
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeNodeParamName(JNIEnv* env, jobject, jint index,
                                                      jint param) {
    bagosynths::Node* n = engine().graph().nodeAt(index);
    return env->NewStringUTF(n ? n->paramName(param) : "");
}

JNIEXPORT jfloat JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeGetNodeParam(JNIEnv*, jobject, jint index,
                                                     jint param) {
    bagosynths::Node* n = engine().graph().nodeAt(index);
    return n ? n->getParam(param) : 0.0f;
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetNodeParam(JNIEnv*, jobject, jint index,
                                                     jint param, jfloat value) {
    if (bagosynths::Node* n = engine().graph().nodeAt(index)) n->setParam(param, value);
}

// Edges as a flat int array, four per edge: fromNode, fromPin, toNode, toPin.
// Node ids are mapped to ORDINALS, because an id is an allocation counter and
// means nothing across runs -- an ordinal at least survives a patch file.
JNIEXPORT jintArray JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeEdges(JNIEnv* env, jobject) {
    const bagosynths::Graph& g = engine().graph();
    const auto& edges = g.edges();

    auto ordinalOf = [&g](bagosynths::NodeId id) {
        for (int i = 0; i < g.nodeCount(); ++i) {
            if (g.idAt(i) == id) return i;
        }
        return -1;
    };

    std::vector<jint> flat;
    flat.reserve(edges.size() * 4);
    for (const bagosynths::Edge& e : edges) {
        flat.push_back(ordinalOf(e.fromNode));
        flat.push_back(e.fromPin);
        flat.push_back(ordinalOf(e.toNode));
        flat.push_back(e.toPin);
    }

    jintArray out = env->NewIntArray(static_cast<jsize>(flat.size()));
    if (!flat.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    }
    return out;
}

// Stats come back as a float array rather than a formatted string: no allocation
// or text handling on the native side, and the UI decides how to present them.
JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetExclusive(JNIEnv*, jobject, jboolean e) {
    engine().setExclusive(e == JNI_TRUE);
}

JNIEXPORT jfloatArray JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeGetStats(JNIEnv* env, jobject) {
    const AudioEngine::Stats s = engine().stats();

    constexpr int kCount = 35;
    jfloat values[kCount] = {
            s.latencyMs,                             // 0
            s.cpuLoadPercent,                        // 1
            s.callbackMaxMs,                         // 2
            static_cast<jfloat>(s.xRuns),            // 3
            static_cast<jfloat>(s.burstFrames),      // 4
            static_cast<jfloat>(s.bufferFrames),     // 5
            static_cast<jfloat>(s.sampleRate),       // 6
            static_cast<jfloat>(s.activeVoices),     // 7
            static_cast<jfloat>(s.sharingMode),      // 8
            static_cast<jfloat>(s.performanceMode),  // 9
            static_cast<jfloat>(s.usesAAudio),       // 10
            static_cast<jfloat>(s.midiMessages),     // 11
            static_cast<jfloat>(s.running),          // 12
            static_cast<jfloat>(s.probeState),       // 13
            s.roundTripMs,                           // 14
            s.inputPeak,                             // 15
            s.inputLatencyMs,                        // 16
            static_cast<jfloat>(s.loading),          // 17
            static_cast<jfloat>(s.ringUnderruns),    // 18
            s.ringFillMs,                            // 19
            s.renderMaxMs,                           // 20
            s.ringTargetMs,                          // 21
            s.outputPeak,                            // 22
            static_cast<jfloat>(s.deviceId),         // 23
            // Output-path health, for the dropout that needs a replug. NOT a
            // frame count: an int64 of frames stops being representable as a
            // float after about six minutes at 44100, which is precisely the
            // timescale this bug lives on. Seconds-since-frozen is exact for as
            // long as anyone cares.
            s.stalledSeconds,                        // 24
            static_cast<jfloat>(s.timestampOk),      // 25
            static_cast<jfloat>(s.streamState),      // 26
            // Direct USB. usbDeficitMs is the one to watch: wall time minus
            // audio actually put on the wire. A constant is healthy; growth is
            // a hole, however well everything else reads.
            static_cast<jfloat>(s.usbActive),        // 27
            static_cast<jfloat>(s.usbRunning),       // 28
            s.usbDeficitMs,                          // 29
            static_cast<jfloat>(s.usbLate),          // 30
            static_cast<jfloat>(s.usbStarved),       // 31
            static_cast<jfloat>(s.usbErrors),        // 32
            s.usbFeedbackRate,                       // 33
            s.usbMaxGapMs,                           // 34
    };

    jfloatArray array = env->NewFloatArray(kCount);
    if (array != nullptr) {
        env->SetFloatArrayRegion(array, 0, kCount, values);
    }
    return array;
}

}  // extern "C"

extern "C" JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetPreferredRate(JNIEnv*, jobject, jint rate) {
    engine().setPreferredRate(rate);
}

extern "C" JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetPreferredFormat(JNIEnv*, jobject, jint format) {
    engine().setPreferredFormat(format);
}

// ------------------------------------------------------------ direct USB ---
//
// The Kotlin side owns the UsbDeviceConnection: permission, claiming, and
// releasing. Everything that needs the descriptors or talks to the endpoints
// is here, so the parser that plans the claim and the code that opens the
// endpoints are the same code, tested in engine/.

namespace {

namespace usb = bagosynths::usb;

// The configuration to use: the first that the class planners understand.
// usbfs returns every configuration, and on the CP-88 the second is Yamaha's
// vendor one; the kernel picks the class-compliant one, and so does this.
const usb::Config* pickConfig(const usb::Device& d, bool wantAudio, bool wantMidi) {
    for (const auto& c : d.configs) {
        if (wantAudio && planAudioOut(c).ok) return &c;
        if (wantMidi && planMidiIn(c, false).ok) return &c;
    }
    return d.configs.empty() ? nullptr : &d.configs[0];
}

bool parseRaw(JNIEnv* env, jbyteArray raw, usb::Device& out, std::string* why) {
    const jsize n = env->GetArrayLength(raw);
    std::vector<uint8_t> bytes(static_cast<size_t>(n));
    env->GetByteArrayRegion(raw, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
    return usb::parse(bytes.data(), bytes.size(), out, why);
}

bool highSpeed(int fd) {
    const int speed = ioctl(fd, USBDEVFS_GET_SPEED, nullptr);
    return speed >= 3;   // USB_SPEED_HIGH and above use 125 us microframes
}

bool setAlt(int fd, int interfaceNumber, int alt) {
    usbdevfs_setinterface si{};
    si.interface = static_cast<unsigned>(interfaceNumber);
    si.altsetting = static_cast<unsigned>(alt);
    return ioctl(fd, USBDEVFS_SETINTERFACE, &si) == 0;
}

jstring errString(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

}  // namespace

extern "C" {

// The plan, flattened. Layout (keep NativeBridge.UsbPlan in step):
//   0  flags: bit 0 audio out ok, bit 1 MIDI in ok, bit 2 MIDI is UMP
//   1  bConfigurationValue
//   2  audio interface      3  audio alt       4  feedback kind (0/1/2)
//   5  feedback interface   6  feedback alt    7  channels
//   8  subslot bytes        9  bit resolution  10 MIDI interface
//   11 MIDI alt             12 MIDI port count
//   then: port string indices (MIDI port count of them)
//   then: n, the audio "USB interface" interfaces (n of them)
//   then: n, the MIDI "USB interface" interfaces
//   then: n, every interface of the configuration ("USB device")
JNIEXPORT jintArray JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeUsbPlan(JNIEnv* env, jobject, jbyteArray raw,
                                                jboolean preferUmp) {
    usb::Device d;
    std::string why;
    std::vector<jint> v(14, -1);
    v[0] = 0;
    v[12] = 0;
    if (!parseRaw(env, raw, d, &why)) {
        __android_log_print(ANDROID_LOG_ERROR, "bos-usb", "descriptors: %s", why.c_str());
        v.push_back(0); v.push_back(0); v.push_back(0);
    } else if (const usb::Config* c = pickConfig(d, true, true)) {
        const usb::AudioOutPlan a = usb::planAudioOut(*c);
        const usb::MidiInPlan m = usb::planMidiIn(*c, preferUmp == JNI_TRUE);
        v[0] = (a.ok ? 1 : 0) | (m.ok ? 2 : 0) | (m.ok && m.ump ? 4 : 0);
        v[1] = c->value;
        if (a.ok) {
            v[2] = a.interfaceNumber; v[3] = a.alt; v[4] = static_cast<int>(a.feedback);
            v[5] = a.feedbackInterface; v[6] = a.feedbackAlt; v[7] = a.channels;
            v[8] = a.subslotBytes; v[9] = a.bitResolution;
            // Reported separately from the claim list because the two answer
            // different questions: the claim list says what to take, this says
            // which of them is NOT a streaming interface and therefore must not
            // be waited on for the kernel to stop streaming it.
            v[13] = a.controlInterface;
        }
        if (m.ok) {
            v[10] = m.interfaceNumber; v[11] = m.alt;
            v[12] = static_cast<int>(m.portStringIndex.size());
            for (uint8_t s : m.portStringIndex) v.push_back(s);
        }
        v.push_back(static_cast<jint>(a.ok ? a.interfaces.size() : 0));
        if (a.ok) for (int i : a.interfaces) v.push_back(i);
        v.push_back(static_cast<jint>(m.ok ? m.interfaces.size() : 0));
        if (m.ok) for (int i : m.interfaces) v.push_back(i);
        const std::vector<int> all = c->interfaceNumbers();
        v.push_back(static_cast<jint>(all.size()));
        for (int i : all) v.push_back(i);
    } else {
        v.push_back(0); v.push_back(0); v.push_back(0);
    }
    jintArray out = env->NewIntArray(static_cast<jsize>(v.size()));
    if (out) env->SetIntArrayRegion(out, 0, static_cast<jsize>(v.size()), v.data());
    return out;
}

// Starts the direct-USB output. The interfaces in the plan are already
// claimed on `fd`. Returns null on success, or why not.
//
// Order, as snd-usb-audio does it: clock rate first, with the streaming
// interface still at alt 0, then the streaming altsetting(s), then the URBs.
JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeStartUsbAudio(JNIEnv* env, jobject, jint fd,
                                                      jbyteArray raw, jint bufferMs,
                                                      jint preferredRate) {
    usb::Device d;
    std::string why;
    if (!parseRaw(env, raw, d, &why)) return errString(env, "descriptors: " + why);
    const usb::Config* c = pickConfig(d, true, false);
    if (c == nullptr) return errString(env, "no configuration");
    const usb::AudioOutPlan p = usb::planAudioOut(*c);
    if (!p.ok) return errString(env, p.why);

    // --- idle first -----------------------------------------------------------
    // Whatever streamed here before -- the kernel, or the DSP behind it -- may
    // have left the device at its streaming altsetting. Now that the interfaces
    // are ours nobody else can be using them, so put them to alt 0, the
    // zero-bandwidth setting, before the clock is touched: the order
    // snd-usb-audio uses, and the one devices are tested against.
    setAlt(fd, p.interfaceNumber, 0);
    if (p.feedback == usb::Feedback::Implicit) setAlt(fd, p.feedbackInterface, 0);

    // --- the rate -----------------------------------------------------------
    std::vector<uint32_t> rates = p.discreteRates;
    if (p.uac2 && p.controlInterface >= 0 && p.clockSource >= 0) {
        rates = usbctl::uac2Rates(fd, p.controlInterface, p.clockSource);
    }
    uint32_t rate = 0;
    if (!rates.empty()) {
        // The preferred rate if offered; otherwise the highest, which is the
        // AAudio path's policy too (MainActivity.nativeRateOf).
        rate = rates.back();
        for (uint32_t r : rates) if (static_cast<jint>(r) == preferredRate) rate = r;
    }
    if (p.uac2 && p.clockSource >= 0) {
        if (rate != 0 && p.clockProgrammable) {
            usbctl::uac2SetRate(fd, p.controlInterface, p.clockSource, rate);
        }
        // Believe the clock over the plan: a fixed clock ignores SET CUR.
        const uint32_t now = usbctl::uac2GetRate(fd, p.controlInterface, p.clockSource);
        if (now != 0) rate = now;
    } else if (!p.uac2 && rate != 0) {
        usbctl::uac1SetRate(fd, p.out.address, rate);
    }
    if (rate == 0) return errString(env, "could not establish the device's sample rate");

    // --- the streaming altsettings -----------------------------------------
    if (!setAlt(fd, p.interfaceNumber, p.alt)) {
        return errString(env, "SET_INTERFACE on the playback interface failed");
    }
    if (p.feedback == usb::Feedback::Implicit &&
        !setAlt(fd, p.feedbackInterface, p.feedbackAlt)) {
        setAlt(fd, p.interfaceNumber, 0);
        return errString(env, "SET_INTERFACE on the feedback interface failed");
    }

    // --- the stream -----------------------------------------------------------
    UsbAudioOut::Config cfg;
    cfg.fd = fd;
    cfg.highSpeed = highSpeed(fd);
    cfg.outEp = p.out.address;
    cfg.outMaxPacket = p.out.bytesPerService();
    cfg.outInterval = p.out.interval;
    cfg.feedback = static_cast<int>(p.feedback);
    cfg.fbEp = p.feedbackEp.address;
    cfg.fbMaxPacket = p.feedbackEp.bytesPerService();
    cfg.fbInterval = p.feedbackEp.interval;
    if (p.feedback == usb::Feedback::Implicit) {
        // The capture side's own frame size, which need not match playback's.
        if (const auto* in = c->find(p.feedbackInterface, p.feedbackAlt)) {
            cfg.fbFrameBytes = std::max(1, in->channels) * std::max(1, in->subslotBytes);
        }
    }
    cfg.channels = p.channels;
    cfg.subslotBytes = p.subslotBytes;
    cfg.rate = static_cast<int>(rate);
    cfg.bufferMs = bufferMs;
    // THE LAST BIG STRUCTURAL DIFFERENCE FROM usbhost, and it has never been
    // varied. usbhost queues 8 IN URBs, 16 ms; this queues 512, 1024 ms. That
    // is 64x the isochronous IN ring for the controller to walk every frame,
    // on the same device as the OUT endpoint that must not miss its deadline.
    // Settable so it can be A/B'd against usbhost's own depth.
    cfg.inBufferMs = gInDepthMs.load(std::memory_order_relaxed);
    // ONE PACKET PER OUT URB, EIGHT PER IN URB. Not symmetry, and not taste:
    // the OUT number buys slack and the IN number saves wake-ups.
    //
    // Slack -- how long this process may be away before the wire goes silent --
    // is `(urbs - 1) x packets`, measured exactly linear. At the same 8 ms of
    // latency, 8x1 / 4x2 / 2x4 give 7 / 6 / 4 ms of it, so the finest division
    // of the same depth is simply the best one. The old 2 came with a note that
    // 1 ms URBs "are worse", which was measured against a transport that
    // invented a size when it fell behind; this one cannot.
    cfg.packetsPerUrb = gOutPktsPerUrb.load(std::memory_order_relaxed);
    cfg.inPacketsPerUrb = gInPktsPerUrb.load(std::memory_order_relaxed);
    cfg.fifoLength = gFifoLen.load(std::memory_order_relaxed);
    cfg.fifoPreRoll = gFifoPreRoll.load(std::memory_order_relaxed);
    cfg.fifoResync = gFifoResync.load(std::memory_order_relaxed) != 0;
    cfg.resyncGapUs = gResyncGapUs.load(std::memory_order_relaxed);

    std::string startErr;
    if (!engine().startUsb(cfg, &startErr)) {
        setAlt(fd, p.interfaceNumber, 0);
        if (p.feedback == usb::Feedback::Implicit) setAlt(fd, p.feedbackInterface, 0);
        return errString(env, startErr.empty() ? "direct USB start failed" : startErr);
    }
    return nullptr;
}

// CLEAR_FEATURE(ENDPOINT_HALT) on both streaming endpoints.
//
// A rung on the recovery ladder. THE LATCH -- one zero-length OUT packet is
// enough to cause it, proved by injection -- survives the UAC teardown: alt 0,
// re-establish the clock, alt 1, restart the stream, all of it, and the device
// stays silent. So the wedge is below the streaming interface, and this asks
// whether it is endpoint state. GET_STATUS says the endpoint is not halted, but
// clearing it also resets the data toggle and the host controller's endpoint
// context, which is the cheapest thing left that is not a reset.
JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeUsbClearHalt(JNIEnv* env, jobject, jint fd,
                                                      jbyteArray raw) {
    usb::Device d;
    if (fd < 0 || !parseRaw(env, raw, d, nullptr)) return JNI_FALSE;
    const usb::Config* c = pickConfig(d, true, false);
    if (c == nullptr) return JNI_FALSE;
    const usb::AudioOutPlan p = usb::planAudioOut(*c);
    if (!p.ok) return JNI_FALSE;
    bool all = true;
    unsigned ep = p.out.address;
    if (ioctl(fd, USBDEVFS_CLEAR_HALT, &ep) != 0) all = false;
    if (p.feedback == usb::Feedback::Implicit) {
        unsigned fb = p.feedbackEp.address;
        if (ioctl(fd, USBDEVFS_CLEAR_HALT, &fb) != 0) all = false;
    }
    __android_log_print(ANDROID_LOG_INFO, "bos-usb",
                        "clear halt: out 0x%02x fb 0x%02x -> %s", p.out.address,
                        p.feedbackEp.address, all ? "ok" : "at least one refused");
    return all ? JNI_TRUE : JNI_FALSE;
}

// Stops the direct-USB output and puts the streaming interfaces back to alt 0,
// the zero-bandwidth setting, so the bus bandwidth is actually released.
JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeStopUsbAudio(JNIEnv* env, jobject, jint fd,
                                                     jbyteArray raw) {
    engine().stop();
    usb::Device d;
    if (fd < 0 || !parseRaw(env, raw, d, nullptr)) return;
    if (const usb::Config* c = pickConfig(d, true, false)) {
        const usb::AudioOutPlan p = usb::planAudioOut(*c);
        if (p.ok) {
            setAlt(fd, p.interfaceNumber, 0);
            if (p.feedback == usb::Feedback::Implicit) setAlt(fd, p.feedbackInterface, 0);
        }
    }
}

// Opens direct-USB MIDI in on `fd` (the MIDI streaming interface claimed).
// `port` is the cable (1.0) or group (2.0). Null on success.
JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeStartUsbMidi(JNIEnv* env, jobject, jint fd,
                                                     jbyteArray raw, jboolean preferUmp,
                                                     jint port) {
    usb::Device d;
    std::string why;
    if (!parseRaw(env, raw, d, &why)) return errString(env, "descriptors: " + why);
    const usb::Config* c = pickConfig(d, false, true);
    if (c == nullptr) return errString(env, "no configuration");
    const usb::MidiInPlan m = usb::planMidiIn(*c, preferUmp == JNI_TRUE);
    if (!m.ok) return errString(env, m.why);
    // MIDI 2.0 devices power up at alt 0, MIDI 1.0; UMP must be selected.
    if (m.alt != 0 && !setAlt(fd, m.interfaceNumber, m.alt)) {
        return errString(env, "SET_INTERFACE to the UMP alternate setting failed");
    }
    if (!engine().midi().openUsb(fd, m.in.address, m.in.maxPacket(), m.ump, port,
                                 &engine().usbOut(),
                                 gMidiUrbs.load(std::memory_order_relaxed))) {
        return errString(env, "could not start the MIDI reader");
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativePrepareGraph(JNIEnv*, jobject) {
    engine().prepareGraph();
}

JNIEXPORT void JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeSetMasterDb(JNIEnv*, jobject, jfloat db) {
    engine().setMasterDb(db);
}

JNIEXPORT jfloat JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeGetMasterDb(JNIEnv*, jobject) {
    return engine().masterDb();
}

// GET_INTERFACE: the altsetting the device reports, -1 if it will not say.
JNIEXPORT jint JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeUsbAltSetting(JNIEnv*, jobject, jint fd, jint iface) {
    return usbctl::currentAltSetting(fd, iface);
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeUsbReconnectKernel(JNIEnv*, jobject, jint fd,
                                                           jint iface) {
    return usbctl::reconnectKernelDriver(fd, iface) ? JNI_TRUE : JNI_FALSE;
}

// Asks the DEVICE how it is, over the control pipe.
//
// Everything in the data path is blind to THE LATCH -- measured, repeatedly:
// frames advancing at exactly 44100/s, deficit frozen, errors 0, altsetting
// still 1, implicit feedback still alternating 44/45. Isochronous OUT has no
// handshake, so "playing" and "acknowledging and discarding" are the same
// picture from here.
//
// These three questions go to the device and come back with an answer:
//   endpoint halt   -- did the OUT endpoint STALL
//   clock valid     -- UAC2's own "is my clock locked" bit
//   sample rate     -- did the device quietly move off 44100
//
// -1 anywhere means the control transfer itself failed, which would be the
// loudest finding of all: it would mean the function is wedged rather than
// merely quiet.
JNIEXPORT jintArray JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeUsbHealthProbe(JNIEnv* env, jobject, jint fd,
                                                       jbyteArray raw) {
    jint out[4] = {-1, -1, -1, -1};
    usb::Device d;
    std::string why;
    if (parseRaw(env, raw, d, &why)) {
        if (const usb::Config* c = pickConfig(d, true, true)) {
            const usb::AudioOutPlan a = usb::planAudioOut(*c);
            if (a.ok) {
                out[0] = usbctl::endpointHalted(fd, a.out.address);
                if (a.uac2 && a.controlInterface >= 0 && a.clockSource >= 0) {
                    out[1] = usbctl::uac2ClockValid(fd, a.controlInterface, a.clockSource);
                    out[2] = static_cast<jint>(
                            usbctl::uac2GetRate(fd, a.controlInterface, a.clockSource));
                }
                out[3] = usbctl::currentAltSetting(fd, a.interfaceNumber);
            }
        }
    }
    __android_log_print(ANDROID_LOG_INFO, "bos-usb",
                        "device health: epHalted=%d clockValid=%d rate=%d alt=%d",
                        out[0], out[1], out[2], out[3]);
    jintArray arr = env->NewIntArray(4);
    if (arr != nullptr) env->SetIntArrayRegion(arr, 0, 4, out);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeUsbReset(JNIEnv*, jobject, jint fd) {
    return usbctl::resetDevice(fd) ? JNI_TRUE : JNI_FALSE;
}

// The ALSA names, so Kotlin never builds one itself and they cannot drift
// from the rule that is tested in engine/.
JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeAlsaCardName(JNIEnv* env, jobject, jstring product,
                                                     jint vid, jint pid) {
    const char* p = product ? env->GetStringUTFChars(product, nullptr) : nullptr;
    const std::string name = usb::alsaCardName(p ? p : "", static_cast<uint16_t>(vid),
                                               static_cast<uint16_t>(pid));
    if (p) env->ReleaseStringUTFChars(product, p);
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT jstring JNICALL
Java_be_mdw_bagosynths_NativeBridge_nativeAlsaMidiPortName(JNIEnv* env, jobject, jstring card,
                                                         jstring jack, jint index) {
    const char* c = card ? env->GetStringUTFChars(card, nullptr) : nullptr;
    const char* j = jack ? env->GetStringUTFChars(jack, nullptr) : nullptr;
    const std::string name = usb::alsaMidiPortName(c ? c : "", j ? j : "", index);
    if (c) env->ReleaseStringUTFChars(card, c);
    if (j) env->ReleaseStringUTFChars(jack, j);
    return env->NewStringUTF(name.c_str());
}

}  // extern "C"
