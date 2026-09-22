#include <bagosynths/UsbDescriptors.h>

#include <algorithm>
#include <cstdio>

namespace bagosynths::usb {

namespace {

// Descriptor types (USB 2.0 ch. 9, and the audio class's CS_ types).
constexpr uint8_t kDevice = 0x01;
constexpr uint8_t kConfiguration = 0x02;
constexpr uint8_t kInterface = 0x04;
constexpr uint8_t kEndpoint = 0x05;
constexpr uint8_t kCsInterface = 0x24;
constexpr uint8_t kCsEndpoint = 0x25;

// Audio control subtypes.
constexpr uint8_t kAcInputTerminal = 0x02;
constexpr uint8_t kAcOutputTerminal = 0x03;
constexpr uint8_t kAcClockSource = 0x0A;
constexpr uint8_t kAcClockSelector = 0x0B;
constexpr uint8_t kAcClockMultiplier = 0x0C;

// Audio streaming subtypes.
constexpr uint8_t kAsGeneral = 0x01;
constexpr uint8_t kAsFormatType = 0x02;

// MIDI streaming subtypes.
constexpr uint8_t kMsHeader = 0x01;
constexpr uint8_t kMsInJack = 0x02;
constexpr uint8_t kMsOutJack = 0x03;
constexpr uint8_t kMsGeneral = 0x01;      // CS_ENDPOINT, MIDI 1.0
constexpr uint8_t kMsGeneral2 = 0x02;     // CS_ENDPOINT, MIDI 2.0

inline uint16_t le16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
inline uint32_t le24(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16);
}
inline uint32_t le32(const uint8_t* p) {
    return le24(p) | (static_cast<uint32_t>(p[3]) << 24);
}

void setError(std::string* error, const char* what, size_t at) {
    if (error == nullptr) return;
    char buf[96];
    std::snprintf(buf, sizeof(buf), "%s at offset %zu", what, at);
    *error = buf;
}

// Class-specific interface descriptor, interpreted by the interface it follows.
void csInterface(AltSetting& a, const uint8_t* d, uint8_t len) {
    if (len < 3) return;
    const uint8_t sub = d[2];

    if (a.isAudioControl()) {
        if (sub == kAcInputTerminal && len >= 8) {
            // UAC2 carries bCSourceID at 7; UAC1 has no clocks at all.
            a.terminals.push_back({d[3], le16(d + 4),
                                   static_cast<uint8_t>(a.isUac2() ? d[7] : 0), true});
        } else if (sub == kAcOutputTerminal && len >= 9) {
            a.terminals.push_back({d[3], le16(d + 4),
                                   static_cast<uint8_t>(a.isUac2() ? d[8] : 0), false});
        } else if (sub == kAcClockSource && len >= 6) {
            a.clocks.push_back({d[3], d[4], d[5]});
        } else if (sub == kAcClockSelector && len >= 5) {
            AltSetting::ClockSelector s{d[3], {}};
            const int pins = d[4];
            for (int i = 0; i < pins && 5 + i < len; ++i) s.sources.push_back(d[5 + i]);
            a.clockSelectors.push_back(s);
        } else if (sub == kAcClockMultiplier && len >= 5) {
            a.clockMultipliers.push_back({d[3], d[4]});
        }
        return;
    }

    if (a.isAudioStreaming()) {
        if (sub == kAsGeneral) {
            if (a.isUac2() && len >= 16) {
                a.terminalLink = d[3];
                a.formatType = d[5];
                a.pcm = (le32(d + 6) & 0x1u) != 0;       // bmFormats bit 0: PCM
                a.channels = d[10];
            } else if (!a.isUac2() && len >= 7) {
                a.terminalLink = d[3];
                a.pcm = le16(d + 5) == 0x0001;           // wFormatTag PCM
            }
        } else if (sub == kAsFormatType && len >= 4) {
            a.formatType = d[3];
            if (a.isUac2() && len >= 6) {
                a.subslotBytes = d[4];
                a.bitResolution = d[5];
            } else if (!a.isUac2() && len >= 8) {
                a.channels = d[4];
                a.subslotBytes = d[5];
                a.bitResolution = d[6];
                const int n = d[7];
                if (n == 0 && len >= 14) {               // continuous range
                    a.rateMin = le24(d + 8);
                    a.rateMax = le24(d + 11);
                } else {
                    for (int i = 0; i < n && 8 + 3 * i + 2 < len; ++i) {
                        a.discreteRates.push_back(le24(d + 8 + 3 * i));
                    }
                }
            }
        }
        return;
    }

    if (a.isMidiStreaming()) {
        if (sub == kMsHeader && len >= 5) {
            a.bcdMSC = le16(d + 3);
        } else if (sub == kMsInJack && len >= 6) {
            a.jacks.push_back({false, d[3] == 0x01, d[4], d[5]});
        } else if (sub == kMsOutJack && len >= 7) {
            // iJack follows the (source id, source pin) pairs.
            const int pins = d[5];
            const int at = 6 + 2 * pins;
            a.jacks.push_back({true, d[3] == 0x01, d[4],
                               static_cast<uint8_t>(at < len ? d[at] : 0)});
        }
    }
}

void csEndpoint(AltSetting& a, Endpoint& e, const uint8_t* d, uint8_t len) {
    if (!a.isMidiStreaming() || len < 4) return;
    const int n = d[3];
    if (d[2] == kMsGeneral && a.bcdMSC < 0x0200) {
        for (int i = 0; i < n && 4 + i < len; ++i) e.midiJacks.push_back(d[4 + i]);
    } else if (d[2] == kMsGeneral2) {
        for (int i = 0; i < n && 4 + i < len; ++i) e.midiBlocks.push_back(d[4 + i]);
    }
}

// Walks one configuration's descriptors, starting at the configuration
// descriptor itself. Returns bytes consumed, 0 on a broken buffer.
size_t walkConfig(const uint8_t* data, size_t length, Config& out, std::string* error) {
    if (length < 9 || data[1] != kConfiguration) {
        setError(error, "expected a configuration descriptor", 0);
        return 0;
    }
    const size_t total = std::min<size_t>(le16(data + 2), length);
    out.value = data[5];
    out.alts.clear();

    size_t i = 0;
    AltSetting* cur = nullptr;
    Endpoint* ep = nullptr;
    while (i + 2 <= total) {
        const uint8_t len = data[i];
        const uint8_t type = data[i + 1];
        if (len < 2 || i + len > total) {
            setError(error, "descriptor overruns its configuration", i);
            return 0;
        }
        const uint8_t* d = data + i;
        if (type == kInterface && len >= 9) {
            AltSetting a;
            a.number = d[2];
            a.alt = d[3];
            a.cls = d[5];
            a.subclass = d[6];
            a.protocol = d[7];
            a.iInterface = d[8];
            out.alts.push_back(a);
            cur = &out.alts.back();
            ep = nullptr;
        } else if (type == kEndpoint && len >= 7 && cur != nullptr) {
            Endpoint e;
            e.address = d[2];
            e.attributes = d[3];
            e.maxPacketRaw = le16(d + 4);
            e.interval = d[6];
            if (len >= 9) e.syncAddress = d[8];
            cur->endpoints.push_back(e);
            ep = &cur->endpoints.back();
        } else if (type == kCsInterface && cur != nullptr) {
            csInterface(*cur, d, len);
        } else if (type == kCsEndpoint && cur != nullptr && ep != nullptr) {
            csEndpoint(*cur, *ep, d, len);
        }
        // Everything else -- IADs, HID, vendor blobs -- is skipped by length.
        i += len;
    }
    return total;
}

// Follows selectors and multipliers down to a clock source. A selector's live
// position is only readable with a control request, so this takes its first
// input -- the one a device powers up on in every case this has met.
const AltSetting::Clock* resolveClock(const AltSetting& ac, int id, int depth = 0) {
    if (depth > 8) return nullptr;              // a loop in a broken descriptor
    for (const auto& c : ac.clocks) if (c.id == id) return &c;
    for (const auto& s : ac.clockSelectors) {
        if (s.id == id && !s.sources.empty()) return resolveClock(ac, s.sources[0], depth + 1);
    }
    for (const auto& m : ac.clockMultipliers) {
        if (m.id == id) return resolveClock(ac, m.source, depth + 1);
    }
    return nullptr;
}

}  // namespace

std::vector<int> Config::interfaceNumbers() const {
    std::vector<int> n;
    for (const auto& a : alts) {
        if (std::find(n.begin(), n.end(), a.number) == n.end()) n.push_back(a.number);
    }
    std::sort(n.begin(), n.end());
    return n;
}

const AltSetting* Config::find(int number, int alt) const {
    for (const auto& a : alts) if (a.number == number && a.alt == alt) return &a;
    return nullptr;
}

bool parseConfig(const uint8_t* data, size_t length, Config& out, std::string* error) {
    return walkConfig(data, length, out, error) != 0;
}

bool parse(const uint8_t* data, size_t length, Device& out, std::string* error) {
    out = Device{};
    if (length < 18 || data[0] < 18 || data[1] != kDevice) {
        setError(error, "expected an 18-byte device descriptor", 0);
        return false;
    }
    out.bcdUSB = le16(data + 2);
    out.cls = data[4];
    out.vendorId = le16(data + 8);
    out.productId = le16(data + 10);
    out.iManufacturer = data[14];
    out.iProduct = data[15];
    out.iSerial = data[16];

    size_t i = data[0];
    while (i + 9 <= length) {
        if (data[i] == 0) break;                // zero padding after the last config
        Config c;
        const size_t used = walkConfig(data + i, length - i, c, error);
        if (used == 0) return false;
        out.configs.push_back(std::move(c));
        i += used;
    }
    if (out.configs.empty()) {
        setError(error, "no configuration descriptor", i);
        return false;
    }
    return true;
}

// ---------------------------------------------------------------- plans ---

AudioOutPlan planAudioOut(const Config& config) {
    AudioOutPlan plan;

    // The best OUT streaming alternate setting.
    const AltSetting* best = nullptr;
    const Endpoint* bestEp = nullptr;
    for (const auto& a : config.alts) {
        if (!a.isAudioStreaming() || !a.pcm || a.formatType != 1) continue;
        if (a.subslotBytes < 2 || a.subslotBytes > 4 || a.channels < 1) continue;
        const Endpoint* out = nullptr;
        for (const auto& e : a.endpoints) {
            if (!e.isIn() && e.transferType() == 1) { out = &e; break; }
        }
        if (out == nullptr) continue;
        const auto better = [&](const AltSetting& x, const AltSetting& y) {
            // Stereo-or-more before mono; then widest sample; then lowest number.
            if ((x.channels >= 2) != (y.channels >= 2)) return x.channels >= 2;
            if (x.bitResolution != y.bitResolution) return x.bitResolution > y.bitResolution;
            return x.number < y.number;
        };
        if (best == nullptr || better(a, *best)) { best = &a; bestEp = out; }
    }
    if (best == nullptr) {
        plan.why = "no PCM playback streaming interface";
        return plan;
    }

    plan.interfaceNumber = best->number;
    plan.alt = best->alt;
    plan.out = *bestEp;
    plan.channels = best->channels;
    plan.subslotBytes = best->subslotBytes;
    plan.bitResolution = best->bitResolution;
    plan.uac2 = best->isUac2();
    plan.discreteRates = best->discreteRates;
    plan.interfaces.push_back(best->number);

    // --- how the stream is clocked -----------------------------------------
    //
    // 1. Explicit: an IN endpoint in the same alt with usage "feedback", or,
    //    on UAC1, the OUT endpoint's bSynchAddress naming one.
    for (const auto& e : best->endpoints) {
        const bool usageFb = e.isIn() && e.transferType() == 1 && e.usageType() == 1;
        const bool uac1Sync = e.isIn() && e.transferType() == 1 &&
                              bestEp->syncAddress != 0 && e.address == bestEp->syncAddress;
        if (usageFb || uac1Sync) {
            plan.feedback = Feedback::Explicit;
            plan.feedbackEp = e;
            plan.feedbackInterface = best->number;
            plan.feedbackAlt = best->alt;
            break;
        }
    }

    // 2. Implicit: an asynchronous OUT with no feedback endpoint is clocked by
    //    an IN data endpoint flagged "implicit feedback". The kernel's generic
    //    rule looks at the next interface first; so does this.
    if (plan.feedback == Feedback::None && bestEp->syncType() == 1) {
        const AltSetting* fb = nullptr;
        const Endpoint* fbEp = nullptr;
        for (const auto& a : config.alts) {
            if (!a.isAudioStreaming() || a.number == best->number) continue;
            for (const auto& e : a.endpoints) {
                if (e.isIn() && e.transferType() == 1 && e.usageType() == 2) {
                    const bool next = a.number == best->number + 1;
                    if (fb == nullptr || (next && fb->number != best->number + 1)) {
                        fb = &a;
                        fbEp = &e;
                    }
                }
            }
        }
        if (fb != nullptr) {
            plan.feedback = Feedback::Implicit;
            plan.feedbackEp = *fbEp;
            plan.feedbackInterface = fb->number;
            plan.feedbackAlt = fb->alt;
            plan.interfaces.push_back(fb->number);
        }
    }

    // --- UAC2: where the clock is -------------------------------------------
    if (plan.uac2) {
        for (const auto& a : config.alts) {
            if (!a.isAudioControl()) continue;
            for (const auto& t : a.terminals) {
                if (t.id != best->terminalLink) continue;
                plan.controlInterface = a.number;
                if (const auto* c = resolveClock(a, t.clockSource)) {
                    plan.clockSource = c->id;
                    // bmControls bits 0-1: frequency control, 0b11 = read/write.
                    plan.clockProgrammable = (c->controls & 0x03) == 0x03;
                }
            }
        }

        // AudioControl is part of what this use NEEDS, not a neighbour it can
        // leave to the kernel.
        //
        // On UAC2 the clock is an entity on AudioControl, so the request that
        // sets the sample rate is addressed to THAT interface. usbfs refuses a
        // control transfer to an interface this process has not claimed, and
        // the refusal is EBUSY. That is the whole of the bug Michiel hit as a
        // connect/disconnect loop with audio on USB interface and MIDI on ALSA:
        //
        //     clock 1: GET RANGE failed: Device or resource busy
        //     direct audio failed: could not establish the device's sample rate
        //
        // followed by the watchdog re-routing every two seconds forever. USB
        // DEVICE mode never hit it because it claims every interface anyway.
        //
        // UAC1 is deliberately NOT included: there the rate is set with a
        // request to the streaming ENDPOINT, which this plan already claims, so
        // a UAC1 device can still be driven while the kernel keeps AudioControl
        // and the ALSA card with it.
        if (plan.controlInterface >= 0) {
            bool have = false;
            for (int i : plan.interfaces) have = have || i == plan.controlInterface;
            if (!have) plan.interfaces.push_back(plan.controlInterface);
        }
    }

    plan.ok = true;
    return plan;
}

MidiInPlan planMidiIn(const Config& config, bool preferUmp) {
    MidiInPlan plan;

    const AltSetting* chosen = nullptr;
    const Endpoint* chosenEp = nullptr;
    for (const auto& a : config.alts) {
        if (!a.isMidiStreaming()) continue;
        const Endpoint* in = nullptr;
        for (const auto& e : a.endpoints) {
            // Bulk, or interrupt, which MIDI 2.0 allows.
            if (e.isIn() && (e.transferType() == 2 || e.transferType() == 3)) { in = &e; break; }
        }
        if (in == nullptr) continue;
        const bool ump = a.bcdMSC >= 0x0200;
        if (chosen == nullptr) { chosen = &a; chosenEp = in; continue; }
        const bool chosenUmp = chosen->bcdMSC >= 0x0200;
        if (ump != chosenUmp && ump == preferUmp) { chosen = &a; chosenEp = in; }
    }
    if (chosen == nullptr) {
        plan.why = "no MIDI streaming interface with an IN endpoint";
        return plan;
    }

    plan.interfaceNumber = chosen->number;
    plan.alt = chosen->alt;
    plan.ump = chosen->bcdMSC >= 0x0200;
    plan.in = *chosenEp;
    plan.interfaces.push_back(chosen->number);

    if (plan.ump) {
        // One port per group terminal block. Their names live in a separate
        // class-specific descriptor fetched by request, not in the
        // configuration, so the plan cannot name them itself.
        const size_t n = std::max<size_t>(1, chosenEp->midiBlocks.size());
        plan.portStringIndex.assign(n, 0);
    } else {
        // One port per cable, in the order the endpoint lists its jacks.
        for (uint8_t id : chosenEp->midiJacks) {
            uint8_t s = 0;
            for (const auto& j : chosen->jacks) if (j.id == id) { s = j.iJack; break; }
            plan.portStringIndex.push_back(s);
        }
        if (plan.portStringIndex.empty()) plan.portStringIndex.push_back(0);
    }

    plan.ok = true;
    return plan;
}

// --------------------------------------------------------------- naming ---

namespace {

// snd-usb-audio names things into 32-byte buffers; ALSA shows at most 31.
std::string alsaTruncate(std::string s) {
    if (s.size() > 31) s.resize(31);
    return s;
}

std::string trim(const std::string& s) {
    const auto b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return {};
    const auto e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

}  // namespace

std::string alsaCardName(const std::string& productString, uint16_t vendorId,
                         uint16_t productId) {
    const std::string p = trim(productString);
    if (!p.empty()) return alsaTruncate(p);
    char buf[40];
    // The kernel's own format, "%#04x" included -- which prints 0x0499 as
    // "0x499", because the width counts the prefix.
    std::snprintf(buf, sizeof(buf), "USB Device %#04x:%#04x", vendorId, productId);
    return alsaTruncate(buf);
}

std::string alsaMidiPortName(const std::string& cardName, const std::string& jackString,
                             int portIndex) {
    char buf[96];
    if (!jackString.empty()) {
        std::snprintf(buf, sizeof(buf), "%s %s", cardName.c_str(), jackString.c_str());
    } else {
        std::snprintf(buf, sizeof(buf), "%s MIDI %d", cardName.c_str(), portIndex + 1);
    }
    return alsaTruncate(buf);
}

}  // namespace bagosynths::usb
