# BT Remote — Bluetooth HID Trackpad + Keyboard

Biến điện thoại Android thành **chuột + bàn phím Bluetooth thật**. Máy bị
điều khiển (TV/PC/tablet) **không cần cài bất kỳ app hay driver nào** — chỉ
cần có Bluetooth và pair như pair chuột Bluetooth bình thường, vì app này
khiến điện thoại tự giả làm một thiết bị HID chuẩn.

## Vì sao làm được mà không cần cài gì bên nhận

Windows/macOS/Android/most smart TV đều có driver HID (Human Interface
Device) tích hợp sẵn trong hệ điều hành — đó là lý do bất kỳ chuột/bàn phím
Bluetooth nào cắm vào cũng chạy ngay. App dùng Android API
`BluetoothHidDevice` để đăng ký điện thoại như 1 thiết bị HID combo
(chuột + bàn phím), gửi các "report" nhị phân đúng chuẩn USB HID mỗi khi
người dùng thao tác trên trackpad/bàn phím ảo.

## Yêu cầu

- Điện thoại Android **9.0 (API 28) trở lên**, có Bluetooth.
- Máy đích (TV/PC) hỗ trợ Bluetooth (đa số PC/laptop/Android TV đời sau
  2016 đều có, hoặc gắn thêm USB Bluetooth dongle).
- Cần Android Studio để build (dự án Kotlin/Gradle chuẩn, không cần thêm
  SDK ngoài).

## Build APK

```bash
# Mở thư mục này bằng Android Studio (File > Open), đợi Gradle sync xong, rồi:
./gradlew assembleDebug
# APK nằm ở: app/build/outputs/apk/debug/app-debug.apk
```

Hoặc build thẳng và cài vào điện thoại đang cắm USB (đã bật USB debugging):

```bash
./gradlew installDebug
```

## Cách dùng

1. Mở app trên điện thoại → bấm **"1. Đăng ký làm bàn phím + chuột
   Bluetooth"** → cấp quyền Bluetooth khi được hỏi.
2. Trên TV/PC: vào Cài đặt Bluetooth → Add device / Pair device → tìm đúng
   **tên thiết bị này** (chính là tên Bluetooth đang đặt trên điện thoại bạn,
   KHÔNG cố định là "BT Remote") → pair như pair chuột Bluetooth bình thường.
   *(Một số máy sẽ hiện mã PIN xác nhận — đồng ý trên cả 2 bên nếu được hỏi.)*
3. Quay lại app, bấm **"Chọn thiết bị"** → chọn tên TV/PC vừa pair.
   *(Từ lần mở app sau, app tự nhớ và kết nối lại thiết bị này ngay khi
   đăng ký HID xong — không cần chọn lại, trừ khi muốn đổi sang thiết bị khác.)*
4. Xong — dùng ngay:
   - **Trackpad**: kéo 1 ngón để di chuyển con trỏ, chạm nhẹ = click trái,
     giữ lâu = click phải, kéo 2 ngón theo chiều dọc = cuộn trang.
   - **Hàng nút dưới cùng** (cố định vị trí, không bị bàn phím ảo đẩy lên):
     - `Chọn thiết bị` / `Home` / `⌨` (mở bàn phím ảo để gõ chữ)
     - `⏻` tắt màn hình *(chỉ hoạt động với TV)* / `−` `+` chỉnh âm lượng
     - `⏮` `⏭` `⏪` `⏩` — bài trước / bài tiếp / tua lùi / tua tới, dùng
       cho trình phát nhạc, video (YouTube, Spotify, VLC...)

## Gõ tiếng Việt có dấu

Chuẩn HID keyboard chỉ gửi được mã phím vật lý (a-z, 0-9...), không gửi
được thẳng ký tự Unicode có dấu — đây là giới hạn của giao thức, không
riêng gì app này. Cách app xử lý: khi gõ, văn bản có dấu sẽ **tự động
chuyển sang chuỗi gõ kiểu Telex** trước khi gửi đi (vd "chào" → gửi đi
"chaof"), tính năng này luôn bật mặc định.

**Điều kiện để nó ra chữ có dấu đúng:** máy đích (TV/PC) phải đang **bật
sẵn bộ gõ Telex**:
- **Windows**: có sẵn layout "Vietnamese (Telex)" miễn phí, không cần cài
  gì — vào Settings > Time & Language > Language & region > Add a
  language > Vietnamese > mở Options, thêm keyboard "Vietnamese (Telex)".
  Trước khi gõ nhớ **chuyển ngôn ngữ gõ đang active sang Vietnamese**
  (icon ngôn ngữ ở khay hệ thống hoặc Win+Space), gõ xong có thể chuyển
  lại tiếng Anh.
- Nếu máy đã có sẵn Unikey/EVKey ở chế độ Telex thì cũng dùng được ngay,
  không cần đổi gì thêm.
- Nếu máy đích **không có** bộ gõ Telex nào đang bật, các ký tự có dấu sẽ
  ra đúng chuỗi Telex thô (không dấu, có thêm ký tự s/f/r/x/j) chứ không
  tự ráp dấu được.

## Giới hạn hiện tại (có thể mở rộng thêm)

- Bảng mã HID (`KeyMapper.kt`) map được chữ cái, số, khoảng trắng và vài
  ký hiệu cơ bản; ký tự lạ khác sẽ bị bỏ qua khi gõ.
- Việc gõ có dấu phụ thuộc vào bộ gõ Telex **đang bật sẵn ở máy đích**
  (xem mục trên) — app không thể tự bật hộ vì không cài gì lên máy đó.
- Nút tắt màn hình (Power) chỉ hoạt động đúng với TV (Android TV/Google
  TV/smart TV) — không đảm bảo hoạt động với PC/laptop.

## Cấu trúc project

```
bt_remote/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/btremote/
        │   ├── MainActivity.kt       — UI, xin quyền, nối các thành phần
        │   ├── HidManager.kt         — đăng ký HID, gửi report chuột/phím/media
        │   ├── HidDescriptor.kt      — report descriptor chuẩn USB HID (bàn phím,
        │   │                           chuột, Consumer Control cho volume/power/media)
        │   ├── KeyMapper.kt          — bảng chuyển ký tự -> HID keycode
        │   ├── VietnameseTelex.kt    — chuyển văn bản có dấu sang chuỗi Telex
        │   └── TrackpadView.kt       — custom View bắt cử chỉ chạm
        └── res/
            ├── layout/activity_main.xml
            └── values/{strings,styles}.xml
```
