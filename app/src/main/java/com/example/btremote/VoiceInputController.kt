package com.example.btremote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Nghe liên tục — tự restart sau mỗi phiên (onResults hoặc lỗi nhẹ).
 * Chỉ dừng hẳn sau [SILENCE_TIMEOUT_MS] im lặng hoặc khi user bấm tắt.
 */
class VoiceInputController(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onStopped: (sentEnter: Boolean) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val silenceRunnable = Runnable { stop(sentEnter = true) }
    private val restartRunnable = Runnable { startRecognizer() }

    var isListening = false
        private set

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        isListening = true
        resetSilenceTimer()
        startRecognizer()
    }

    private fun destroyRecognizer() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun startRecognizer() {
        if (!isListening) return
        destroyRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(bundle: Bundle?) {
                    val text = firstResult(bundle) ?: return
                    onPartialText(text)
                    resetSilenceTimer()
                }

                override fun onResults(bundle: Bundle?) {
                    if (!isListening) return
                    firstResult(bundle)?.let(onPartialText)
                    // Destroy trước rồi mới schedule restart
                    destroyRecognizer()
                    resetSilenceTimer()
                    handler.removeCallbacks(restartRunnable)
                    handler.postDelayed(restartRunnable, 300)
                }

                override fun onError(error: Int) {
                    if (!isListening) return
                    val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                            error == SpeechRecognizer.ERROR_CLIENT
                    if (fatal) {
                        stop(sentEnter = false)
                        return
                    }
                    // ERROR_RECOGNIZER_BUSY: đợi lâu hơn
                    val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800L else 300L
                    destroyRecognizer()
                    handler.removeCallbacks(restartRunnable)
                    handler.postDelayed(restartRunnable, delay)
                }
            })
            startListening(buildIntent())
        }
    }

    fun stop(sentEnter: Boolean) {
        if (!isListening) return
        handler.removeCallbacks(silenceRunnable)
        handler.removeCallbacks(restartRunnable)
        isListening = false
        destroyRecognizer()
        onStopped(sentEnter)
    }

    fun destroy() {
        handler.removeCallbacks(silenceRunnable)
        handler.removeCallbacks(restartRunnable)
        destroyRecognizer()
    }

    private fun resetSilenceTimer() {
        handler.removeCallbacks(silenceRunnable)
        handler.postDelayed(silenceRunnable, SILENCE_TIMEOUT_MS)
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
    }

    companion object {
        private const val SILENCE_TIMEOUT_MS = 2500L
    }
}
