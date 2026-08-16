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
    private val silenceRunnable = Runnable { stop(sentEnter = true) }
    private val listenAgainRunnable = Runnable { listenAgain() }

    var isListening = false
        private set

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // Recognizer đã thật sự sẵn sàng nghe -> reset lại đồng hồ đếm im
            // lặng từ đây, không tính thời gian khởi động trước đó vào hạn chót.
            resetSilenceTimer()
        }
        override fun onBeginningOfSpeech() {
            // Đã bắt đầu nghe thấy có người nói -> chắc chắn không phải im lặng,
            // reset để tránh tắt oan khi bộ nhận diện xử lý hơi lâu.
            resetSilenceTimer()
        }
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
            // CHỈ coi là lỗi chết (tắt hẳn mic) khi thật sự không thể tiếp tục:
            // thiếu quyền micro. ERROR_CLIENT KHÔNG còn bị coi là lỗi chết nữa —
            // đây thực ra là lỗi TẠM THỜI rất hay gặp khi startListening() được
            // gọi ngay sau cancel() mà RecognitionService của máy chưa kịp dọn
            // xong phiên cũ (đặc biệt các máy đời thấp / ROM tuỳ biến). Trước
            // đây cứ gặp ERROR_CLIENT là tắt mic luôn, không thử lại -> đúng
            // triệu chứng "lúc dùng được lúc không" vì lỗi này xảy ra ngẫu
            // nhiên tuỳ thời điểm, không phải lỗi vĩnh viễn.
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stop(sentEnter = false)
                return
            }
            // ERROR_RECOGNIZER_BUSY / ERROR_CLIENT: đợi lâu hơn cho hệ thống kịp dọn phiên cũ.
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 800L
                else -> 300L
            }
            handler.removeCallbacks(listenAgainRunnable)
            handler.postDelayed(listenAgainRunnable, delay)
        }
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        isListening = true
        resetSilenceTimer()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener)
            }
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
            // KHÔNG gọi cancel() ngay sát trước startListening() nữa. Gọi 2 lệnh
            // này liên tiếp trong cùng 1 lượt xử lý khiến RecognitionService của
            // hệ thống (đặc biệt máy đời thấp / ROM tuỳ biến) chưa kịp dọn xong
            // phiên cũ đã nhận lệnh mới -> hay trả về ERROR_CLIENT ngẫu nhiên,
            // đúng là nguồn gốc triệu chứng "lúc dùng được lúc không". Phiên
            // trước khi tới đây đã kết thúc rồi (listenAgain chỉ được gọi sau
            // onResults/onError hoặc lúc start() lần đầu), nên không cần cancel().
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
        // KHÔNG destroy() ở đây nữa — xem "LƯU Ý QUAN TRỌNG #2" ở đầu file.
        // Chỉ dừng nghe, giữ nguyên instance để lần bấm bật tiếp theo dùng lại.
        try { recognizer?.cancel() } catch (_: Exception) {}
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
        // 2500ms la qua ngan: bo nhan dien giong noi (dac biet tieng Viet) thuong
        // can vai giay de khoi dong va nhan ra chu dau tien. Truoc day chi 2.5s
        // khong co chu nao la tu dong TAT HAN mic -> dung trieu chung "bam len
        // may giay no tat, khong thu duoc chu nao". Tang len 8s va chi tinh la
        // "im lang that su" (khong con reset) sau khi da onReadyForSpeech/
        // onBeginningOfSpeech/onPartialResults roi ma van khong co gi them.
        private const val SILENCE_TIMEOUT_MS = 8000L
    }
}
