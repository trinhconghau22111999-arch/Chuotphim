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
    const val CONSUMER_MUTE: Int = 0x04
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

        // ---- Consumer Control (Report ID 3) — Volume Up/Down + Mute ----
        // Dùng usage page riêng (0x0C - Consumer) thay vì nhét tạm vào bảng phím
        // (0x07 - Keyboard) như trước: trước đây "Usage Maximum" của bàn phím chỉ
        // khai báo tới 0x65 nên hệ điều hành sẽ BỎ QUA mọi keycode ngoài khoảng đó,
        // khiến phím Volume gửi đi vô tác dụng dù report có gửi thành công.
        0x05, 0x0C,             // Usage Page (Consumer)
        0x09, 0x01,             // Usage (Consumer Control)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85.toByte(), ID_CONSUMER, //   Report ID (3)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95.toByte(), 0x08,    //   Report Count (8) -> vừa đủ 1 byte, không cần đệm
        0x09, 0xE9.toByte(),    //   Usage (Volume Increment)
        0x09, 0xEA.toByte(),    //   Usage (Volume Decrement)
        0x09, 0xE2.toByte(),    //   Usage (Mute)
        0x09, 0x30,             //   Usage (Power)
        0x09, 0xB6.toByte(),    //   Usage (Scan Previous Track)
        0x09, 0xB5.toByte(),    //   Usage (Scan Next Track)
        0x09, 0xB4.toByte(),    //   Usage (Rewind)
        0x09, 0xB3.toByte(),    //   Usage (Fast Forward)
        0x81.toByte(), 0x02,    //   Input (Data,Var,Abs) -> 8 bit thật, đủ 1 byte
        0xC0.toByte()           // End Collection
    )
}
