package com.example.btremote

/**
 * Bảng chuyển ký tự thường gõ -> (HID usage code, có cần giữ Shift hay không).
 * Theo chuẩn USB HID Usage Tables, bảng "Keyboard/Keypad Page (0x07)".
 */
object KeyMapper {

    const val MOD_SHIFT: Int = 0x02

    // Phím đặc biệt dùng cho các nút cứng — chỉ khai báo đúng 4 phím đang thực sự
    // được gọi tới (ENTER/BACKSPACE qua SyncInputController, HOME/ESC qua nút Home/Back).
    val SPECIAL: Map<String, Int> = mapOf(
        "ENTER" to 0x28,
        "BACKSPACE" to 0x2A,
        "ESC" to 0x29,
        "HOME" to 0x4A,
    )

    /** Trả về (keycode, canGõĐược). canGõĐược=false nghĩa là ký tự này chưa hỗ trợ. */
    fun charToKeycode(c: Char): Pair<Int, Boolean>? {
        return when (c) {
            in 'a'..'z' -> Pair(0x04 + (c - 'a'), false)
            in 'A'..'Z' -> Pair(0x04 + (c.lowercaseChar() - 'a'), true)
            in '1'..'9' -> Pair(0x1E + (c - '1'), false)
            '0' -> Pair(0x27, false)
            ' ' -> Pair(0x2C, false)
            '\n' -> Pair(0x28, false)
            '-' -> Pair(0x2D, false)
            '=' -> Pair(0x2E, false)
            ',' -> Pair(0x36, false)
            '.' -> Pair(0x37, false)
            '/' -> Pair(0x38, false)
            ';' -> Pair(0x33, false)
            '\'' -> Pair(0x34, false)
            '!' -> Pair(0x1E, true)   // Shift+1
            '@' -> Pair(0x1F, true)   // Shift+2
            '#' -> Pair(0x20, true)
            '$' -> Pair(0x21, true)
            '%' -> Pair(0x22, true)
            '?' -> Pair(0x38, true)
            '_' -> Pair(0x2D, true)
            '+' -> Pair(0x2E, true)
            else -> null // Ký tự có dấu tiếng Việt / ký tự lạ: không map được qua HID keycode đơn giản
        }
    }
}
