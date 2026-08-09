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

    fun release() = toneGenerator.release()
}
