package com.example.btremote

/**
 * Bảng chuyển ký tự thường gõ -> (HID usage code, có cần giữ Shift hay không).
 * Theo chuẩn USB HID Usage Tables, bảng "Keyboard/Keypad Page (0x07)".
 */
object KeyMapper {

    const val MOD_SHIFT: Int = 0x02

    // Phím đặc biệt dùng cho các nút cứng — chỉ còn ENTER/BACKSPACE (qua
    // SyncInputController). HOME/ESC đã bỏ khỏi đây: 2 nút Home/Back giờ gửi qua
    // Consumer Control (AC Home/AC Back trong HidDescriptor) thay vì phím bàn phím
    // thường, xem HidManager.sendHome()/sendBack() để biết lý do.
    val SPECIAL: Map<String, Int> = mapOf(
        "ENTER" to 0x28,
        "BACKSPACE" to 0x2A,
        // 4 phím mũi tên (D-pad) — usage chuẩn bàn phím, giống hệt cách 1 bàn phím
        // vật lý cắm vào TV vẫn điều hướng được menu (lên/xuống/trái/phải).
        "UP" to 0x52,
        "DOWN" to 0x51,
        "LEFT" to 0x50,
        "RIGHT" to 0x4F,
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
            // ----- Ký hiệu không cần Shift -----
            '-' -> Pair(0x2D, false)
            '=' -> Pair(0x2E, false)
            '[' -> Pair(0x2F, false)
            ']' -> Pair(0x30, false)
            '\\' -> Pair(0x31, false)
            ';' -> Pair(0x33, false)
            '\'' -> Pair(0x34, false)
            '`' -> Pair(0x35, false)
            ',' -> Pair(0x36, false)
            '.' -> Pair(0x37, false)
            '/' -> Pair(0x38, false)
            // ----- Hàng số, cần Shift -----
            '!' -> Pair(0x1E, true)   // Shift+1
            '@' -> Pair(0x1F, true)   // Shift+2
            '#' -> Pair(0x20, true)   // Shift+3
            '$' -> Pair(0x21, true)   // Shift+4
            '%' -> Pair(0x22, true)   // Shift+5
            '^' -> Pair(0x23, true)   // Shift+6
            '&' -> Pair(0x24, true)   // Shift+7
            '*' -> Pair(0x25, true)   // Shift+8
            '(' -> Pair(0x26, true)   // Shift+9
            ')' -> Pair(0x27, true)   // Shift+0
            // ----- Ký hiệu khác, cần Shift -----
            '_' -> Pair(0x2D, true)
            '+' -> Pair(0x2E, true)
            '{' -> Pair(0x2F, true)
            '}' -> Pair(0x30, true)
            '|' -> Pair(0x31, true)
            ':' -> Pair(0x33, true)
            '"' -> Pair(0x34, true)
            '~' -> Pair(0x35, true)
            '<' -> Pair(0x36, true)
            '>' -> Pair(0x37, true)
            '?' -> Pair(0x38, true)
            else -> null // Ký tự có dấu tiếng Việt / ký tự lạ: không map được qua HID keycode đơn giản
        }
    }
}
