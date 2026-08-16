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
 *
 * LƯU Ý QUAN TRỌNG (fix lỗi "lúc xài được lúc không"):
 * Bản trước đây destroy() rồi createSpeechRecognizer() MỚI lại sau MỖI câu nói
 * (mỗi lần onResults/onError). SpeechRecognizer.createSpeechRecognizer() bind
 * tới 1 Service hệ thống (RecognitionService) theo kiểu bất đồng bộ — nếu
 * destroy() (unbind) rồi create() (bind) lại liên tục trong thời gian ngắn,
 * hệ thống Android (đặc biệt các ROM tuỳ biến như MIUI) có thể ÂM THẦM không
 * bind kịp cho lần sau, không hề gọi bất kỳ callback lỗi nào -> nút mic bật lên
 * (isListening=true) nhưng thực chất không nghe gì cả, trông như "lúc được lúc
 * không" tuỳ may rủi có kịp bind hay không.
 * Sửa: tạo SpeechRecognizer đúng 1 LẦN cho cả phiên nghe liên tục, các lần
 * "nghe lại" sau mỗi câu chỉ gọi lại startListening() trên CÙNG 1 instance đó
 * (đây là cách dùng được Google khuyến nghị cho continuous listening), không
 * destroy+create lại nữa. Chỉ thật sự destroy() khi dừng hẳn (stop()) hoặc khi
 * instance bị lỗi nặng không dùng lại được (recreateAndListen()).
 */
class VoiceInputController(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onStopped: (sentEnter: Boolean) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val silenceRunnable = Runnable { stop(sentEnter = true) }
    private val listenAgainRunnable = Runnable { listenAgain() }

    var isListening = false
        private set

    private val recognitionListener = object : RecognitionListener {
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
            resetSilenceTimer()
            handler.removeCallbacks(listenAgainRunnable)
            handler.postDelayed(listenAgainRunnable, 300)
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
            handler.removeCallbacks(listenAgainRunnable)
            handler.postDelayed(listenAgainRunnable, delay)
        }
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        isListening = true
        resetSilenceTimer()
        destroyRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
        listenAgain()
    }

    /** Nghe lại câu tiếp theo TRÊN CÙNG 1 instance recognizer đang có, không tạo mới. */
    private fun listenAgain() {
        if (!isListening) return
        val current = recognizer
        if (current == null) {
            recreateAndListen()
            return
        }
        try {
            current.cancel()
            current.startListening(buildIntent())
        } catch (e: Exception) {
            // Instance hiện tại bị lỗi không dùng lại được -> đành tạo lại,
            // nhưng đây chỉ là phương án dự phòng, không còn là đường chạy
            // bình thường của mỗi câu nói như bản cũ nữa.
            recreateAndListen()
        }
    }

    private fun recreateAndListen() {
        if (!isListening) return
        destroyRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
        try {
            recognizer?.startListening(buildIntent())
        } catch (e: Exception) {
            handler.removeCallbacks(listenAgainRunnable)
            handler.postDelayed(listenAgainRunnable, 500)
        }
    }

    private fun destroyRecognizer() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    fun stop(sentEnter: Boolean) {
        if (!isListening) return
        handler.removeCallbacks(silenceRunnable)
        handler.removeCallbacks(listenAgainRunnable)
        isListening = false
        destroyRecognizer()
        onStopped(sentEnter)
    }

    fun destroy() {
        handler.removeCallbacks(silenceRunnable)
        handler.removeCallbacks(listenAgainRunnable)
        isListening = false
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
