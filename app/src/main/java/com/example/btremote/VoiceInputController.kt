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
 * [onPartialText] gọi liên tục trong lúc nói, [onStopped] gọi khi dừng hẳn (báo có
 * nên gửi Enter lên TV hay không).
 */
class VoiceInputController(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onStopped: (sentEnter: Boolean) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val silenceRunnable = Runnable { stop(sentEnter = true) }

    var isListening = false
        private set

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return

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
                    stop(sentEnter = true)
                }

                override fun onError(error: Int) {
                    stop(sentEnter = false)
                }
            })
            startListening(buildIntent())
        }
        isListening = true
        resetSilenceTimer()
    }

    /** Dừng phiên nghe hiện tại. [sentEnter] báo cho listener có nên gửi Enter hay không. */
    fun stop(sentEnter: Boolean) {
        if (!isListening) return
        handler.removeCallbacks(silenceRunnable)
        isListening = false
        recognizer?.apply { stopListening(); destroy() }
        recognizer = null
        onStopped(sentEnter)
    }

    fun destroy() {
        handler.removeCallbacks(silenceRunnable)
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
        // Kéo dài mốc tự-dừng của máy để đồng hồ SILENCE_TIMEOUT_MS ở trên luôn là
        // bên quyết định dừng trước.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
    }

    companion object {
        private const val SILENCE_TIMEOUT_MS = 2000L
    }
}
