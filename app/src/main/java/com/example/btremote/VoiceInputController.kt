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
 * Nghe MỘT câu rồi TỰ TẮT — không còn tự mở lại nghe câu tiếp theo nữa.
 * Có 2 mốc tự tắt:
 *  - Chưa nói gì trong [PRE_SPEECH_TIMEOUT_MS]: tắt, không gửi Enter (coi như
 *    bấm nhầm/không nói gì).
 *  - ĐÃ bắt đầu nói, sau đó NGỪNG NÓI liên tục [POST_SPEECH_SILENCE_MS]: tắt
 *    ngay, gửi Enter, chốt câu vừa nhận diện được.
 * Việc tự tắt sau khi ngừng nói được TỰ CANH ở phía app (không chỉ dựa vào
 * onResults của hệ thống), vì nhiều máy/ROM bỏ qua tham số
 * EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS trong intent, dẫn tới
 * onResults có thể không bao giờ tới hoặc tới rất trễ.
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
 * LƯU Ý QUAN TRỌNG #2 (fix lỗi "chỉ dùng được lần đầu, bấm lại lần sau không
 * bao giờ nghe được nữa"):
 * Bản trước vẫn còn destroy() SpeechRecognizer mỗi khi người dùng BẤM TẮT mic
 * (stop()), rồi tạo mới hoàn toàn ở lần bấm BẬT tiếp theo (start()). Trên
 * nhiều máy, sau khi 1 SpeechRecognizer đã bị destroy() (unbind khỏi
 * RecognitionService của hệ thống), lần tạo mới kế tiếp KHÔNG bind lại được
 * nữa — không phải do rate-limit tạm thời như lưu ý #1 ở trên, mà là kẹt VĨNH
 * VIỄN cho tới khi khởi động lại app, y hệt triệu chứng "chỉ dùng lần đầu, lần
 * sau không bao giờ dùng được nữa".
 * Sửa: chỉ tạo SpeechRecognizer ĐÚNG 1 LẦN trong suốt vòng đời của
 * VoiceInputController (lazy, ở lần start() đầu tiên). Bấm tắt (stop()) từ
 * giờ CHỈ cancel()/stopListening(), KHÔNG còn destroy() nữa — instance vẫn
 * còn sống, sẵn sàng cho lần bấm bật tiếp theo dùng lại ngay. Chỉ thật sự
 * destroy() khi cả Activity bị huỷ (MainActivity.onDestroy() gọi
 * VoiceInputController.destroy()).
 */
class VoiceInputController(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onStopped: (sentEnter: Boolean) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    // Chưa nói gì -> tắt, không chốt gì cả.
    private val preSpeechTimeoutRunnable = Runnable { stop(sentEnter = false) }
    // Đã nói rồi, giờ im lặng đủ lâu -> tắt, chốt câu vừa nói.
    private val postSpeechSilenceRunnable = Runnable { stop(sentEnter = true) }
    // Chỉ dùng để RETRY lại đúng phiên đang nghe khi gặp lỗi tạm thời lúc khởi
    // động (KHÔNG còn dùng để mở phiên nghe MỚI cho câu tiếp theo nữa).
    private val retryListenRunnable = Runnable { listenAgain() }

    var isListening = false
        private set

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            resetPreSpeechTimer()
        }

        override fun onBeginningOfSpeech() {
            // Đã bắt đầu nói -> chuyển sang canh mốc "ngừng nói bao lâu thì tắt".
            handler.removeCallbacks(preSpeechTimeoutRunnable)
            resetPostSpeechTimer()
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(bundle: Bundle?) {
            val text = firstResult(bundle) ?: return
            onPartialText(text)
            // Vẫn đang có tiếng nói mới -> dời mốc "ngừng nói 2s" ra xa thêm.
            resetPostSpeechTimer()
        }

        override fun onResults(bundle: Bundle?) {
            if (!isListening) return
            firstResult(bundle)?.let(onPartialText)
            // Hệ thống tự báo đã nhận diện xong câu -> tắt luôn, KHÔNG còn mở
            // lại nghe câu tiếp theo như bản "nghe liên tục" cũ nữa.
            stop(sentEnter = true)
        }

        override fun onError(error: Int) {
            if (!isListening) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stop(sentEnter = false)
                return
            }
            // ERROR_RECOGNIZER_BUSY / ERROR_CLIENT: lỗi TẠM THỜI hay gặp khi
            // recognizer chưa kịp dọn xong phiên cũ (đặc biệt máy đời thấp/ROM
            // tuỳ biến) -> thử khởi động lại ĐÚNG phiên đang nghe này (không
            // phải mở phiên mới cho câu khác), không tắt hẳn mic.
            val retryable = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT
            if (retryable) {
                handler.removeCallbacks(retryListenRunnable)
                handler.postDelayed(retryListenRunnable, 800)
                return
            }
            // Các lỗi còn lại (ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT...) nghĩa là
            // recognizer coi như đã xong việc -> tắt mic, chốt lại những gì đã
            // nhận diện được (nếu có).
            stop(sentEnter = true)
        }
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        isListening = true
        resetPreSpeechTimer()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        listenAgain()
    }

    /** (Re)bắt đầu nghe TRÊN CÙNG 1 instance recognizer đang có, không tạo mới. */
    private fun listenAgain() {
        if (!isListening) return
        val current = recognizer
        if (current == null) {
            recreateAndListen()
            return
        }
        try {
            // KHÔNG gọi cancel() ngay sát trước startListening() — xem lưu ý
            // "lúc xài được lúc không" ở đầu file.
            current.startListening(buildIntent())
        } catch (e: Exception) {
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
            handler.removeCallbacks(retryListenRunnable)
            handler.postDelayed(retryListenRunnable, 500)
        }
    }

    private fun destroyRecognizer() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    fun stop(sentEnter: Boolean) {
        if (!isListening) return
        handler.removeCallbacks(preSpeechTimeoutRunnable)
        handler.removeCallbacks(postSpeechSilenceRunnable)
        handler.removeCallbacks(retryListenRunnable)
        isListening = false
        // KHÔNG destroy() ở đây nữa — xem "LƯU Ý QUAN TRỌNG #2" ở đầu file.
        try { recognizer?.cancel() } catch (_: Exception) {}
        onStopped(sentEnter)
    }

    fun destroy() {
        handler.removeCallbacks(preSpeechTimeoutRunnable)
        handler.removeCallbacks(postSpeechSilenceRunnable)
        handler.removeCallbacks(retryListenRunnable)
        isListening = false
        destroyRecognizer()
    }

    private fun resetPreSpeechTimer() {
        handler.removeCallbacks(preSpeechTimeoutRunnable)
        handler.postDelayed(preSpeechTimeoutRunnable, PRE_SPEECH_TIMEOUT_MS)
    }

    private fun resetPostSpeechTimer() {
        handler.removeCallbacks(postSpeechSilenceRunnable)
        handler.postDelayed(postSpeechSilenceRunnable, POST_SPEECH_SILENCE_MS)
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Uu tien dung bo nhan dien OFFLINE neu may co ho tro -> khoi dong
        // nhanh hon, khong phai cho ket noi server. Chi la GOI Y, khong lo
        // hong tinh nang neu may khong co goi offline.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Bao he thong cung tu ket thuc sau ~2s im lang, dong bo voi
        // POST_SPEECH_SILENCE_MS phia app ben duoi (phong khi may nao do
        // THUC SU tuan theo tham so nay thi cang phan hoi nhanh, con may nao
        // bo qua thi da co dong ho rieng cua app lo).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, POST_SPEECH_SILENCE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POST_SPEECH_SILENCE_MS)
        // Khong ep buoc do dai toi thieu -> cau ngan duoc tra ve ngay, khong
        // phai cho oan (xem lich su sua loi "nhan dien cham" truoc day).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
    }

    companion object {
        // Mo mic ma chua noi gi trong 8s -> tu tat, khong chot gi ca.
        private const val PRE_SPEECH_TIMEOUT_MS = 8000L
        // Da noi roi, ngung noi lien tuc 2s -> tu tat, chot cau vua nhan dien.
        private const val POST_SPEECH_SILENCE_MS = 2000L
    }
}
