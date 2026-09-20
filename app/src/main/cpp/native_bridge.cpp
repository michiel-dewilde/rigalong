#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "AudioEngine.h"

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

    constexpr int kCount = 27;
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
    };

    jfloatArray array = env->NewFloatArray(kCount);
    if (array != nullptr) {
        env->SetFloatArrayRegion(array, 0, kCount, values);
    }
    return array;
}

}  // extern "C"
