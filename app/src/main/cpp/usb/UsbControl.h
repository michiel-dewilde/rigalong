#pragma once

// The handful of control requests the direct-USB modes need, over usbfs.
//
//   * reading and setting the sample rate (UAC2: the clock source; UAC1: the
//     endpoint), because in the direct modes nothing else will;
//   * reading an interface's current alternate setting, to tell whether the
//     kernel or the DSP has really let go of it;
//   * handing an interface back to its kernel driver.
//
// The last one matters more than it looks. Measured on this tablet: after the
// app releases the CP-88's interfaces, snd-usb-audio does NOT rebind to them
// -- the driver link simply goes empty. Leaving a direct mode without this
// would leave the device invisible to Android until it was replugged.

#include <cstdint>
#include <vector>

namespace usbctl {

// UAC2 GET RANGE on the clock's sampling frequency control: every rate the
// clock can run at. Discrete ranges (min == max) are listed as-is; a
// continuous range contributes the common rates that fall inside it.
// Empty on failure.
std::vector<uint32_t> uac2Rates(int fd, int controlInterface, int clockId);

// UAC2 SET CUR / GET CUR on the clock's sampling frequency.
bool uac2SetRate(int fd, int controlInterface, int clockId, uint32_t rate);
uint32_t uac2GetRate(int fd, int controlInterface, int clockId);   // 0 on failure

// --- latch diagnostics ------------------------------------------------------
//
// THE LATCH leaves no trace in the data path: isochronous OUT has no
// handshake, so a device that has stopped rendering looks exactly like one
// that is playing. These three ask the DEVICE directly, over the control pipe,
// which is the only channel that answers back.
//
// Each returns -1 when the transfer itself failed, which is a finding in its
// own right: a wedged audio function might stop answering at all.

// Standard GET_STATUS on an endpoint. Bit 0 is the halt (STALL) condition.
int endpointHalted(int fd, int endpointAddress);

// UAC2 Clock Source, CS_CLOCK_VALID_CONTROL. 1 = the device considers its
// clock locked and valid, 0 = it does not. The closest thing the class has to
// "is your audio function healthy".
int uac2ClockValid(int fd, int controlInterface, int clockId);

// UAC1 SET CUR on the endpoint's sampling frequency control.
bool uac1SetRate(int fd, int endpoint, uint32_t rate);

// Standard GET_INTERFACE: the alternate setting the DEVICE says is active.
// -1 on failure (some devices do not answer it; treat as unknown).
int currentAltSetting(int fd, int interfaceNumber);

// USBDEVFS_CONNECT on one interface: ask the kernel to bind its driver again.
// The interface must already have been released.
bool reconnectKernelDriver(int fd, int interfaceNumber);

// USBDEVFS_RESET: a port reset. Drivers without reset callbacks are unbound
// and rebound, so this is the fallback when reconnecting interface by
// interface does not bring the ALSA card back.
bool resetDevice(int fd);

}  // namespace usbctl
