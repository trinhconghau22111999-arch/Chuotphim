package com.example.btremote

/**
 * Report descriptor chuẩn USB HID cho 1 thiết bị combo: bàn phím (Report ID 1)
 * + chuột (Report ID 2). Mọi hệ điều hành (Windows/macOS/Android/TV có Bluetooth)
 * đều hiểu descriptor này sẵn, không cần cài driver gì thêm — đây chính là lý do
 * cách này hoạt động mà "máy bị điều khiển" không cần cài app.
 */
object HidDescriptor {

    const val ID_KEYBOARD: Byte = 1
    const val ID_MOUSE: Byte = 2
    const val ID_CONSUMER: Byte = 3

    // Bitmap của report Consumer Control (Report ID 3, giờ dùng đủ 8 bit = 1 byte):
    //   bit0=Volume Up, bit1=Volume Down, bit2=Mute, bit3=Power,
    //   bit4=Previous Track, bit5=Next Track, bit6=Rewind, bit7=Fast Forward
    const val CONSUMER_VOLUME_UP: Int = 0x01
    const val CONSUMER_VOLUME_DOWN: Int = 0x02
    // bit2 (0x04) = Mute: vẫn khai báo trong DESCRIPTOR bên dưới (Usage Mute) vì
    // report đã cố định 9 bit theo đúng thứ tự phần cứng, nhưng chưa có nút bấm nào
    // gọi tới nên không khai báo hằng số CONSUMER_MUTE (tránh code chết).
    // Usage "Power" (0x0C/0x30) — trên đa số Android TV/Google TV/smart TV, đây
    // chính là nút nguồn trên remote Bluetooth thật, dùng để tắt màn hình TV.
    // KHÔNG áp dụng cho PC/laptop (Windows/macOS không map usage này thành tắt
    // màn hình, có máy sẽ bỏ qua hoặc hiểu thành tắt máy — chỉ nên dùng với TV).
    const val CONSUMER_POWER: Int = 0x08
    // 4 phím điều khiển media chuẩn (Usage Page Consumer 0x0C):
    //   |◄ Previous Track (0xB6), ►| Next Track (0xB5),
    //   ◄◄ Rewind (0xB4), ►► Fast Forward (0xB3)
    const val CONSUMER_PREV_TRACK: Int = 0x10
    const val CONSUMER_NEXT_TRACK: Int = 0x20
    const val CONSUMER_REWIND: Int = 0x40
    const val CONSUMER_FAST_FORWARD: Int = 0x80
    // Play/Pause (Usage 0xCD) — bit thứ 9, đã hết chỗ ở byte đầu (8 bit dùng hết
    // cho 8 phím trên) nên usage này rơi sang bit0 của BYTE THỨ 2 trong report.
    // sendConsumerControl() ở HidManager tự tách bitmask 16-bit này thành đúng
    // 2 byte khi gửi report, xem giải thích chi tiết ở đó.
    const val CONSUMER_PLAY_PAUSE: Int = 0x100
    // Home/Back HỆ THỐNG — khác hoàn toàn phím Esc/Home của bàn phím thường.
    // Đây là 2 usage "Application Control" chuẩn mà remote Bluetooth thật (Android
    // TV/Google TV) dùng để về Home / lùi lại: Android map thẳng usage này vào hành
    // động hệ thống bất kể app nào đang mở, không phụ thuộc app có lắng nghe phím
    // Esc/Home hay không (đây chính là lý do bản cũ "lúc được lúc không").
    const val CONSUMER_AC_HOME: Int = 0x200  // bit 9
    const val CONSUMER_AC_BACK: Int = 0x400  // bit 10

    val DESCRIPTOR: ByteArray = byteArrayOf(
        // ---- Keyboard (Report ID 1) ----
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x06,             // Usage (Keyboard)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85.toByte(), ID_KEYBOARD, //   Report ID (1)
        0x05, 0x07,             //   Usage Page (Key Codes)
        0x19, 0xE0.toByte(),    //   Usage Minimum (224)
        0x29, 0xE7.toByte(),    //   Usage Maximum (231)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95.toByte(), 0x08,    //   Report Count (8)  -> byte modifier (Ctrl/Shift/Alt...)
        0x81.toByte(), 0x02,    //   Input (Data,Var,Abs)
        0x95.toByte(), 0x01,    //   Report Count (1)
        0x75, 0x08,             //   Report Size (8)
        0x81.toByte(), 0x01,    //   Input (Constant) -> byte reserved
        0x95.toByte(), 0x06,    //   Report Count (6)
        0x75, 0x08,             //   Report Size (8)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x65,             //   Logical Maximum (101)
        0x05, 0x07,             //   Usage Page (Key Codes)
        0x19, 0x00,             //   Usage Minimum (0)
        0x29, 0x65,             //   Usage Maximum (101)
        0x81.toByte(), 0x00,    //   Input (Data,Array) -> 6 phím đang nhấn
        0xC0.toByte(),          // End Collection

        // ---- Mouse (Report ID 2) ----
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x02,             // Usage (Mouse)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x09, 0x01,             //   Usage (Pointer)
        0xA1.toByte(), 0x00,    //   Collection (Physical)
        0x85.toByte(), ID_MOUSE, //     Report ID (2)
        0x05, 0x09,             //     Usage Page (Buttons)
        0x19, 0x01,             //     Usage Minimum (Button 1)
        0x29, 0x03,             //     Usage Maximum (Button 3)
        0x15, 0x00,             //     Logical Minimum (0)
        0x25, 0x01,             //     Logical Maximum (1)
        0x95.toByte(), 0x03,    //     Report Count (3)
        0x75, 0x01,             //     Report Size (1)
        0x81.toByte(), 0x02,    //     Input (Data,Var,Abs) -> 3 nút chuột
        0x95.toByte(), 0x01,    //     Report Count (1)
        0x75, 0x05,             //     Report Size (5)
        0x81.toByte(), 0x01,    //     Input (Constant) -> padding
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30,             //     Usage (X)
        0x09, 0x31,             //     Usage (Y)
        0x09, 0x38,             //     Usage (Wheel)
        0x15, 0x81.toByte(),    //     Logical Minimum (-127)
        0x25, 0x7F,             //     Logical Maximum (127)
        0x75, 0x08,             //     Report Size (8)
        0x95.toByte(), 0x03,    //     Report Count (3)
        0x81.toByte(), 0x06,    //     Input (Data,Var,Rel) -> dx, dy, wheel
        0xC0.toByte(),          //   End Collection
        0xC0.toByte(),          // End Collection

        // ---- Consumer Control (Report ID 3) — Volume Up/Down + Mute + Media ----
        // Dùng usage page riêng (0x0C - Consumer) thay vì nhét tạm vào bảng phím
        // (0x07 - Keyboard) như trước: trước đây "Usage Maximum" của bàn phím chỉ
        // khai báo tới 0x65 nên hệ điều hành sẽ BỎ QUA mọi keycode ngoài khoảng đó,
        // khiến phím Volume gửi đi vô tác dụng dù report có gửi thành công.
        // Report dài 2 byte (16 bit): 9 bit đầu là 9 nút thật (đủ chỗ thêm
        // Play/Pause so với bản cũ chỉ có 8 bit = 8 nút), 7 bit cuối là đệm cho
        // tròn 2 byte.
        0x05, 0x0C,             // Usage Page (Consumer)
        0x09, 0x01,             // Usage (Consumer Control)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85.toByte(), ID_CONSUMER, //   Report ID (3)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95.toByte(), 0x0B,    //   Report Count (11) -> 11 nút thật
        0x09, 0xE9.toByte(),    //   Usage (Volume Increment)
        0x09, 0xEA.toByte(),    //   Usage (Volume Decrement)
        0x09, 0xE2.toByte(),    //   Usage (Mute)
        0x09, 0x30,             //   Usage (Power)
        0x09, 0xB6.toByte(),    //   Usage (Scan Previous Track)
        0x09, 0xB5.toByte(),    //   Usage (Scan Next Track)
        0x09, 0xB4.toByte(),    //   Usage (Rewind)
        0x09, 0xB3.toByte(),    //   Usage (Fast Forward)
        0x09, 0xCD.toByte(),    //   Usage (Play/Pause)
        0x0A, 0x23, 0x02,       //   Usage (AC Home)  -- usage 2 byte (0x0223)
        0x0A, 0x24, 0x02,       //   Usage (AC Back)  -- usage 2 byte (0x0224)
        0x81.toByte(), 0x02,    //   Input (Data,Var,Abs) -> 11 bit thật
        0x95.toByte(), 0x01,    //   Report Count (1)
        0x75, 0x05,             //   Report Size (5)
        0x81.toByte(), 0x01,    //   Input (Constant) -> 5 bit đệm, đủ tròn 2 byte
        0xC0.toByte()           // End Collection
    )
}
