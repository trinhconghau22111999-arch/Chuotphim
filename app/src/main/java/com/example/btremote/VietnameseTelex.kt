package com.example.btremote

import java.text.Normalizer

/**
 * Chuyển văn bản tiếng Việt có dấu (Unicode) sang chuỗi gõ kiểu Telex
 * (chỉ gồm chữ cái a-z thường + phím tone s/f/r/x/j), để gửi qua HID
 * như bàn phím vật lý bình thường.
 *
 * Máy đích cần đang bật bộ gõ Telex (Windows có sẵn layout
 * "Vietnamese (Telex)" trong Settings > Language, không cần cài thêm gì;
 * hoặc máy đã có Unikey/EVKey ở chế độ Telex) thì gõ tới đâu sẽ tự ráp
 * dấu tới đó, giống hệt gõ Telex trên bàn phím thật.
 *
 * Ví dụ: "Xin chào" -> "Xin chaof"
 */
object VietnameseTelex {

    // Dấu thanh (tone marks) -> phím Telex tương ứng
    private val toneMap = mapOf(
        '\u0301' to "s", // sắc
        '\u0300' to "f", // huyền
        '\u0309' to "r", // hỏi
        '\u0303' to "x", // ngã
        '\u0323' to "j",  // nặng
    )

    fun toTelex(input: String): String {
        // đ/Đ không tách được bằng NFD (là ký tự riêng), xử lý trước
        val pre = input.replace("đ", "dd").replace("Đ", "Dd")
        val decomposed = Normalizer.normalize(pre, Normalizer.Form.NFD)

        val sb = StringBuilder()
        var i = 0
        while (i < decomposed.length) {
            val c = decomposed[i]
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) {
                i++ // dấu mồ côi không có chữ cái đi kèm, bỏ qua
                continue
            }
            sb.append(c)
            i++
            // gom mọi dấu kết hợp (mũ/trăng/móc + thanh) đi ngay sau chữ cái này
            while (i < decomposed.length &&
                Character.getType(decomposed[i]) == Character.NON_SPACING_MARK.toInt()
            ) {
                when (val mark = decomposed[i]) {
                    '\u0302' -> sb.append(c)   // â/ê/ô: mũ -> lặp lại chữ cái (aa/ee/oo)
                    '\u0306', '\u031B' -> sb.append('w') // ă/ơ/ư: trăng/móc -> w
                    else -> toneMap[mark]?.let { sb.append(it) }
                }
                i++
            }
        }
        return sb.toString()
    }
}
