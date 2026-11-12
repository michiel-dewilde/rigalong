package com.rigalong

import android.app.Activity
import android.os.Bundle

/**
 * What Android opens when a CP88/CP73 is plugged in: nothing to see, and
 * nothing started.
 *
 * It exists for its entry in the manifest. Android grants USB permission
 * "always" only to the app it would open for that device, and that grant is
 * what brings a replugged keyboard back in the middle of a set without a
 * dialog. Opening the instrument is not part of that: a cable plugged in
 * must not start RigAlong, and must not hand it the keyboard. So this takes
 * the grant, tells a rig that is ALREADY running that its device is back,
 * and is gone. With no rig running the keyboard stays Android's sound card
 * until the player opens RigAlong.
 *
 * Never [Rig.get] here: that would be the start by cable this is here to
 * prevent.
 */
class UsbAttach : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Rig.running()?.router?.deviceAttached()
        // In onCreate: an activity with Theme.NoDisplay has to be finished
        // before it is resumed.
        finish()
    }
}
