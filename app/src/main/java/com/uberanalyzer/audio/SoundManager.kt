package com.uberanalyzer.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.uberanalyzer.model.ScoreRating

class SoundManager(context: Context) {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    fun playForRating(rating: ScoreRating) {
        when (rating) {
            ScoreRating.EXCELLENT, ScoreRating.GOOD -> toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            ScoreRating.AVERAGE -> toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
            else -> toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        }
    }

    fun playHighProfitAlert() {
        try {
            Thread {
                try {
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
                    Thread.sleep(300)
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
                    Thread.sleep(300)
                    toneGenerator.startTone(ToneGenerator.TONE_SUP_PIP, 350)
                } catch (e: Exception) {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
                }
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() = toneGenerator.release()
}
