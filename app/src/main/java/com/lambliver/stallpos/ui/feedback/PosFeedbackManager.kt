package com.lambliver.stallpos.ui.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.lambliver.stallpos.R
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "PosFeedback"
private const val SOUND_VOLUME = 0.6f

internal fun shouldPlaySound(soundEnabled: Boolean, ringerMode: Int): Boolean {
    if (!soundEnabled) return false
    return ringerMode == AudioManager.RINGER_MODE_NORMAL
}

/**
 * 震動 + 音效回饋（Activity 生命週期內單例；由 [rememberPosFeedback] 建立與釋放）。
 * 音效僅在 [AudioManager.RINGER_MODE_NORMAL] 且 sound 開關開啟時播放（USAGE_MEDIA）。
 * [loadSounds] 須在背景執行緒呼叫，避免 SoundPool 建立阻塞 main thread。
 */
class PosFeedbackManager private constructor(
    private val vibrator: Vibrator?,
    private val audioManager: AudioManager?,
    private var hapticEnabled: Boolean,
    private var soundEnabled: Boolean,
) {
    @Volatile
    private var soundPool: SoundPool? = null
    private val soundReady = ConcurrentHashMap<Int, Boolean>()
    @Volatile private var addSoundId = 0
    @Volatile private var successSoundId = 0
    @Volatile private var errorSoundId = 0
    @Volatile private var released = false

    init {
        Log.d(TAG, "init called")
    }

    fun updatePreferences(hapticEnabled: Boolean, soundEnabled: Boolean) {
        this.hapticEnabled = hapticEnabled
        this.soundEnabled = soundEnabled
    }

    /** 背景執行緒初始化 SoundPool；重複呼叫為 no-op。 */
    fun loadSounds(context: Context) {
        if (released || soundPool != null) return
        Log.d(TAG, "loadSounds on ${Thread.currentThread().name}")
        val pool = buildSoundPool()
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) soundReady[sampleId] = true
        }
        val addId = pool.load(context, R.raw.pos_add, 1)
        val successId = pool.load(context, R.raw.pos_success, 1)
        val errorId = pool.load(context, R.raw.pos_error, 1)
        synchronized(this) {
            if (released) {
                pool.release()
                return
            }
            soundPool = pool
            addSoundId = addId
            successSoundId = successId
            errorSoundId = errorId
        }
    }

    fun release() {
        synchronized(this) {
            if (released) return
            released = true
            soundPool?.release()
            soundPool = null
        }
    }

    /** 加入購物車：40ms 短震 + 短 click */
    fun lightTap() {
        vibrateLightTap()
        playSound(addSoundId)
    }

    /** 結帳成功：2×pulse + chime */
    fun checkoutSuccess() {
        vibrateCheckoutSuccess()
        playSound(successSoundId)
    }

    /** 錯誤／庫存不足：200ms 長震 + bump */
    fun error() {
        vibrateError()
        playSound(errorSoundId)
    }

    private fun mayPlaySound(): Boolean {
        if (soundPool == null) return false
        val ringerMode = audioManager?.ringerMode ?: return false
        return shouldPlaySound(soundEnabled, ringerMode)
    }

    private fun playSound(sampleId: Int) {
        if (!mayPlaySound() || sampleId == 0) return
        if (soundReady[sampleId] != true) return
        soundPool?.play(sampleId, SOUND_VOLUME, SOUND_VOLUME, 1, 0, 1f)
    }

    private fun vibrateLightTap() {
        if (!hapticEnabled) return
        vibrateOneShot(40L, 160)
    }

    private fun vibrateCheckoutSuccess() {
        if (!hapticEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 40L, 80L, 40L),
                    intArrayOf(0, 160, 0, 160),
                    -1,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0L, 40L, 80L, 40L), -1)
        }
    }

    private fun vibrateError() {
        if (!hapticEnabled) return
        vibrateOneShot(200L, 255)
    }

    private fun vibrateOneShot(ms: Long, amplitude: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(ms)
        }
    }

    companion object {
        fun create(context: Context): PosFeedbackManager {
            Log.d(TAG, "create called")
            return PosFeedbackManager(
                vibrator = systemVibrator(context),
                audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager,
                hapticEnabled = true,
                soundEnabled = true,
            )
        }

        /** Compose 測試用：不震、不響、無資源。 */
        fun noop(): PosFeedbackManager = PosFeedbackManager(
            vibrator = null,
            audioManager = null,
            hapticEnabled = false,
            soundEnabled = false,
        )

        private fun buildSoundPool(): SoundPool =
            SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()

        private fun systemVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
    }
}
