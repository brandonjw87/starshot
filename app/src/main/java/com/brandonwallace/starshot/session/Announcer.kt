package com.brandonwallace.starshot.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Loud countdown/announcements on the ALARM stream so they are audible even with media volume down. */
class Announcer(ctx: Context, private val voice: Boolean, private val vibrate: Boolean, private val maxVolume: Boolean) {
    private val app = ctx.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)
    private val vib = app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private var savedVolume = -1
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    suspend fun init() {
        if (maxVolume) runCatching {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
        if (voice) {
            val ready = CompletableDeferred<Boolean>()
            tts = TextToSpeech(app) { ready.complete(it == TextToSpeech.SUCCESS) }
            ttsReady = withTimeoutOrNull(4000) { ready.await() } ?: false
            if (ttsReady) tts?.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                language = Locale.getDefault()
                setSpeechRate(1.15f)
            }
        }
    }

    fun say(text: String) { if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text) }

    private fun buzz(ms: Long) { if (vibrate) runCatching { vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) } }

    /** One countdown second: beep (higher/longer for the last 3), spoken number, short buzz. */
    fun tick(n: Int, speak: Boolean = true) {
        if (n <= 3) tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 350) else tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        if (speak) say(n.toString())
        buzz(if (n <= 3) 150 else 60)
    }

    /** Distinct "shutter open" signal. Vibration happens before exposure starts (caller waits). */
    fun go(text: String = "Shooting. Step away.") {
        tone?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 900)
        say(text)
        buzz(400)
    }

    suspend fun done(text: String) {
        repeat(3) { tone?.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 500); delay(700) }
        say(text)
        buzz(600)
        delay(3500)
    }

    fun error(text: String) { tone?.startTone(ToneGenerator.TONE_SUP_ERROR, 800); say(text) }

    fun release() {
        runCatching { tts?.stop(); tts?.shutdown() }
        runCatching { tone?.release() }
        if (savedVolume >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
    }
}
