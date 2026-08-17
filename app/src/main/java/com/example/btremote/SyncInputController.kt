package com.example.btremote

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.EditText

/**
 * Gắn vào ô [input]: mỗi khi nội dung đổi (gõ tay hoặc dán/chèn từ code), so với
 * phần đã gửi lên TV lần trước — giữ nguyên phần đầu giống nhau, phần đuôi cũ mất
 * đi thì gửi BACKSPACE, phần mới thêm thì gửi qua [HidManager.typeText]. Nhờ vậy
 * ô nhập luôn khớp với thứ vừa gửi, kể cả khi dán nguyên khối văn bản có dấu.
 */
class SyncInputController(
    private val input: EditText,
    private val hidManager: HidManager
) {
    private var sentText = ""
    private var isProgrammaticChange = false

    init {
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isProgrammaticChange) return
                sendDiff(s?.toString().orEmpty())
            }
        })

        // Enter thật từ bàn phím ảo: một số IME gửi KeyEvent, số khác gửi qua editor action.
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                hidManager.sendSpecialKey("ENTER"); true
            } else false
        }
        input.setOnEditorActionListener { _, _, _ -> hidManager.sendSpecialKey("ENTER"); true }
    }

    private fun sendDiff(newText: String) {
        if (newText == sentText) return

        var commonPrefix = 0
        val maxCommon = minOf(sentText.length, newText.length)
        while (commonPrefix < maxCommon && sentText[commonPrefix] == newText[commonPrefix]) commonPrefix++

        repeat(sentText.length - commonPrefix) { hidManager.sendSpecialKey("BACKSPACE") }
        val added = newText.substring(commonPrefix)
        if (added.isNotEmpty()) hidManager.typeText(added)

        sentText = newText
    }

    /** Xoá trắng ô nhập mà KHÔNG gửi backspace lên TV — dùng khi ô nhập trên TV coi
     *  như đã đổi/mất focus (di chuột, bấm Home...), nên không còn gì để đồng bộ nữa. */
    fun reset() {
        if (sentText.isEmpty() && input.text.isNullOrEmpty()) return
        setTextSilently("")
        sentText = ""
    }

    /** Xoá TOÀN BỘ nội dung, gửi đủ số BACKSPACE để xoá luôn phần đã gửi lên TV —
     *  dùng cho nút bấm "xoá" tường minh của người dùng (khác với reset(), vốn chỉ
     *  dọn ô nhập tại app mà không đụng gì tới TV). */
    fun clearAll() {
        repeat(sentText.length) { hidManager.sendSpecialKey("BACKSPACE") }
        setTextSilently("")
        sentText = ""
    }

    /** Chèn 1 dấu cách vào cuối văn bản hiện tại nếu chưa có sẵn khoảng trắng —
     *  gọi trước khi bắt đầu 1 phiên nhập giọng nói MỚI, để câu nói tiếp theo
     *  không bị dính liền vào chữ đã có trước đó (đi qua watcher bình thường nên
     *  dấu cách này cũng được gửi lên TV, giữ đồng bộ). */
    fun ensureTrailingSpace() {
        val text = input.text ?: return
        if (text.isNotEmpty() && !text.last().isWhitespace()) {
            text.append(' ')
            input.setSelection(text.length)
        }
    }

    /** Chèn văn bản tại vị trí con trỏ hiện tại (dùng cho paste). Watcher tự lo gửi lên TV. */
    fun insertText(text: String) {
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(0)
        input.text?.replace(minOf(start, end), maxOf(start, end), text)
        input.setSelection((minOf(start, end) + text.length).coerceAtMost(input.text?.length ?: 0))
    }

    /** Thay toàn bộ đoạn từ [position] tới hết bằng [text] (dùng cho preview giọng nói
     *  đang nói dở — mỗi kết quả tạm là toàn câu tính từ lúc bắt đầu nói). */
    fun replaceFrom(position: Int, text: String) {
        val editable = input.text ?: return
        val end = editable.length
        val start = position.coerceAtMost(end)
        editable.replace(start, end, text)
        input.setSelection(input.text?.length ?: 0)
    }

    fun cursorPosition(): Int = input.text?.length ?: 0

    private fun setTextSilently(text: String) {
        isProgrammaticChange = true
        input.setText(text)
        isProgrammaticChange = false
    }
}
