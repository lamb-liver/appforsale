package com.lambliver.appforsale

import android.media.AudioManager
import com.lambliver.appforsale.ui.feedback.shouldPlaySound
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosFeedbackManagerTest {

    @Test
    fun soundDisabled_returnsFalse_regardlessOfRingerMode() {
        assertFalse(
            shouldPlaySound(
                soundEnabled = false,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
            ),
        )
    }

    @Test
    fun normalMode_withSoundEnabled_returnsTrue() {
        assertTrue(
            shouldPlaySound(
                soundEnabled = true,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
            ),
        )
    }

    @Test
    fun vibrateMode_returnsFalse() {
        assertFalse(
            shouldPlaySound(
                soundEnabled = true,
                ringerMode = AudioManager.RINGER_MODE_VIBRATE,
            ),
        )
    }

    @Test
    fun silentMode_returnsFalse() {
        assertFalse(
            shouldPlaySound(
                soundEnabled = true,
                ringerMode = AudioManager.RINGER_MODE_SILENT,
            ),
        )
    }
}
