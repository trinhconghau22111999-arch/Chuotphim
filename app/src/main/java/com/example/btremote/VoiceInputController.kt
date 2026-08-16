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
 * Nghe liên tục qua SpeechRecognizer (không dùng hộp thoại hệ thống) và tự dừng
 * sau [SILENCE_TIMEOUT_MS] không có kết quả tạm mới — coi như người dùng đã nói xong.
 * Khi gặp lỗi không nghiêm trọng (NO_MATCH, SPEECH_TIMEOUT...) tự restart để nghe tiếp,
 * thay vì dừng hẳn như trước (nguyên nhân nút mic tắt sau ~2s chưa nói gì).
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

    private fun startRecognizer() {
        if (!isListening) return
        recognizer?.destroy()
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
                    firstResult(bundle)?.let(onPartialText)
                    // Có kết quả cuối → restart để tiếp tục nghe
                    resetSilenceTimer()
                    handler.post { startRecognizer() }
                }

                override fun onError(error: Int) {
                    // Lỗi nghiêm trọng (thiết bị, mạng, quyền) → dừng hẳn
                    val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    if (fatal || !isListening) {
                        stop(sentEnter = false)
                    } else {
                        // Lỗi nhẹ (NO_MATCH, SPEECH_TIMEOUT, SERVER, NETWORK...) → restart sau 200ms
                        handler.removeCallbacks(restartRunnable)
                        handler.postDelayed(restartRunnable, 200)
                    }
                }
            })
            startListening(buildIntent())
        }
    }

    /** Dừng phiên nghe hiện tại. [sentEnter] báo cho listener có nên gửi Enter hay không. */
    fun stop(sentEnter: Boolean) {
        if (!isListening) return
        handler.removeCallbacks(silenceRunnable)
        handler.removeCallbacks(restartRunnable)
        isListening = false
        recognizer?.apply { stopListening(); destroy() }
        recognizer = null
        onStopped(sentEnter)
    }

    fun destroy() {
        handler.removeCallbacks(silenceRunnable)
        handler.removeCallbacks(restartRunnable)
        recognizer?.destroy()
        recognizer = null
    }

    private fun resetSilenceTimer() {
        handler.removeCallbacks(silenceRunnable)
        handler.postDelayed(silenceRunnable, SILENCE_TIMEOUT_MS)
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
    }

    companion object {
        private const val SILENCE_TIMEOUT_MS = 2000L
    }
}
