package com.example.btremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class HidManager(private val context: Context) {

    interface Listener {
        fun onRegistered()
        fun onUnregistered()
        /** [wasIntentional] = true nếu ngắt này là do chính app chủ động gọi
         *  connectTo() sang thiết bị khác (không phải do ra xa/mất tín hiệu). */
        fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean, wasIntentional: Boolean = false)
        fun onError(message: String)
    }

    // @Volatile: được ghi từ thread callback Binder của hệ thống (hidCallback,
    // serviceListener) nhưng đọc từ nhiều thread khác (UI thread khi gửi report
    // chuột/media, và thread nền keySender khi gõ phím) — không đánh dấu volatile
    // thì 1 thread có thể đọc giá trị cũ do JIT/CPU cache, dù hiếm khi gây lỗi rõ
    // rệt trên thực tế vẫn nên đảm bảo đúng theo mô hình bộ nhớ của Java/Kotlin.
    @Volatile private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    // Địa chỉ thiết bị mà app vừa chủ động gọi disconnect() (vì user chọn thiết bị
    // khác) — dùng để phân biệt với trường hợp thiết bị tự rớt vì ra xa.
    @Volatile private var pendingIntentionalDisconnectAddress: String? = null
    val isConnected: Boolean get() = connectedDevice != null
    val currentConnectedAddress: String? get() = connectedDevice?.address
    // true nếu app còn giữ proxy HID hợp lệ với hệ thống (đã registerApp thành
    // công và chưa bị huỷ). Khi quay lại từ đa nhiệm, nếu hệ thống đã ngầm ngắt
    // profile lúc chạy nền (onServiceDisconnected -> hidDevice = null), giá trị
    // này về false -> MainActivity biết cần đăng ký lại từ đầu (xem onResume()).
    val isRegistered: Boolean get() = hidDevice != null
    var listener: Listener? = null

    private val prefs = context.getSharedPreferences("bt_remote_prefs", Context.MODE_PRIVATE)

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "Remote TV Bluetooth",             // tên hiển thị khi pair
        "Trackpad + Keyboard qua Bluetooth HID",
        "RemoteTVBluetooth",
        BluetoothHidDevice.SUBCLASS1_COMBO, // báo là combo mouse+keyboard
        HidDescriptor.DESCRIPTOR
    )

    private val qos = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
        800, 9, 0, 11250, 0
    )

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) listener?.onRegistered() else listener?.onUnregistered()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val connected = state == BluetoothProfile.STATE_CONNECTED
            val wasIntentional = !connected && device != null &&
                device.address == pendingIntentionalDisconnectAddress
            if (!connected) pendingIntentionalDisconnectAddress = null
            connectedDevice = if (connected) device else null
            if (connected && device != null) {
                // Nhớ lại thiết bị vừa kết nối thành công -> lần mở app sau tự kết nối
                // lại luôn, không cần vào "Chọn thiết bị" chọn lại từ đầu.
                prefs.edit().putString(PREF_LAST_DEVICE, device.address).apply()
            }
            listener?.onConnectionStateChanged(device, connected, wasIntentional)
        }
    }

    /** Bước 1: lấy proxy tới profile HID_DEVICE của hệ thống. */
    fun start() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            listener?.onError("Thiết bị này không có Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            listener?.onError("Vui lòng bật Bluetooth trước")
            return
        }
        val ok = try {
            adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            listener?.onError("Thiếu quyền Bluetooth: ${e.message}")
            return
        }
        if (!ok) listener?.onError("Máy này không hỗ trợ chế độ HID Device")
    }

    private fun registerApp() {
        val executor = Executor { command -> command.run() }
        try {
            hidDevice?.registerApp(sdpSettings, null, qos, executor, hidCallback)
        } catch (e: Exception) {
            listener?.onError("Không đăng ký được HID: ${e.message}")
        }
    }

    fun unregister() {
        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.w(TAG, "unregisterApp lỗi (bỏ qua vì đang thoát app)", e)
        }
        // Huỷ hết report gõ phím còn đang chờ trong hàng đợi (nếu có) khi app thoát.
        keySender.shutdownNow()
    }

    /** Yêu cầu kết nối tới 1 thiết bị đã pair (TV/PC) — gọi sau khi user chọn trong danh sách bonded devices. */
    fun connectTo(device: BluetoothDevice) {
        try {
            // Ngắt thiết bị đang kết nối (nếu có) trước khi connect thiết bị mới
            if (connectedDevice != null && connectedDevice?.address != device.address) {
                pendingIntentionalDisconnectAddress = connectedDevice?.address
                hidDevice?.disconnect(connectedDevice!!)
            }
            hidDevice?.connect(device)
        } catch (e: Exception) {
            listener?.onError("Không kết nối được tới thiết bị: ${e.message}")
        }
    }

    fun bondedDevices(): Set<BluetoothDevice> {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            emptySet()
        }
    }

    /**
     * Tự kết nối lại thiết bị đã kết nối thành công gần nhất (nếu vẫn còn trong danh
     * sách đã pair của máy) — gọi ngay sau khi đăng ký HID xong, để người dùng không
     * phải vào "Chọn thiết bị" chọn lại mỗi lần mở app. Nếu chưa từng kết nối lần nào,
     * hoặc thiết bị đó không còn pair nữa, hàm này không làm gì cả (im lặng bỏ qua).
     */
    fun autoReconnectLastDevice() {
        val address = prefs.getString(PREF_LAST_DEVICE, null) ?: return
        val device = bondedDevices().firstOrNull { it.address == address } ?: return
        connectTo(device)
    }

    // ---------- Gửi report chuột ----------

    fun sendMouseMove(dx: Int, dy: Int) {
        sendMouseReport(0, dx, dy, 0)
    }

    fun sendMouseClick(rightButton: Boolean) {
        val bit = if (rightButton) 0x02 else 0x01
        // Nhấn xuống rồi nhả ra qua keySender (giống bàn phím) thay vì bắn liền 2
        // report không nghỉ trên main thread — cùng lý do đã sửa cho bàn phím: TV
        // cần 1 khoảng nghỉ nhỏ mới nhận diện đúng nhấn/nhả, tránh thỉnh thoảng bị
        // "lì chuột" (nhả không tới nơi, coi như đang giữ chuột).
        safeExecuteKeySender {
            sendMouseReport(bit, 0, 0, 0)
            if (sleepPaced(KEY_HOLD_MS)) sendMouseReport(0, 0, 0, 0)
        }
    }

    fun sendMouseScroll(wheel: Int) {
        sendMouseReport(0, 0, 0, wheel)
    }

    private fun sendMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val device = connectedDevice ?: return
        val report = byteArrayOf(
            buttons.toByte(),
            clampByte(dx),
            clampByte(dy),
            clampByte(wheel)
        )
        safeSendReport(device, HidDescriptor.ID_MOUSE.toInt(), report)
    }

    private fun clampByte(v: Int): Byte = v.coerceIn(-127, 127).toByte()

    // ---------- Gửi report Consumer Control (Volume/Media) ----------

    /** Tăng âm lượng — dùng report descriptor "Consumer Control" riêng (Report ID 3),
     *  không còn đi qua bảng phím như bản cũ nên hệ điều hành nhận đúng chuẩn. */
    fun sendVolumeUp() = sendConsumerControl(HidDescriptor.CONSUMER_VOLUME_UP)

    fun sendVolumeDown() = sendConsumerControl(HidDescriptor.CONSUMER_VOLUME_DOWN)

    /** Nút nguồn (Power) — trên đa số TV Bluetooth (Android TV/Google TV/smart TV)
     *  usage này chính là tắt/mở màn hình TV. Không đảm bảo hoạt động với PC. */
    fun sendScreenOff() = sendConsumerControl(HidDescriptor.CONSUMER_POWER)

    // 4 phím điều khiển media chuẩn — hoạt động với hầu hết trình phát nhạc/video
    // trên TV, PC, điện thoại (Windows Media Player, YouTube, Spotify, VLC...).
    fun sendPreviousTrack() = sendConsumerControl(HidDescriptor.CONSUMER_PREV_TRACK)
    fun sendNextTrack() = sendConsumerControl(HidDescriptor.CONSUMER_NEXT_TRACK)
    fun sendRewind() = sendConsumerControl(HidDescriptor.CONSUMER_REWIND)
    fun sendFastForward() = sendConsumerControl(HidDescriptor.CONSUMER_FAST_FORWARD)
    fun sendPlayPause() = sendConsumerControl(HidDescriptor.CONSUMER_PLAY_PAUSE)

    /** Home/Back hệ thống — gửi qua Consumer Control (AC Home / AC Back) giống hệt
     *  remote Bluetooth thật, thay vì phím Esc/Home của bàn phím thường (KeyMapper
     *  cũ) vốn chỉ có tác dụng với 1 số app có ô nhập liệu, không phải hành động
     *  Home/Back hệ thống -> đây là lý do bản cũ "lúc được lúc không". */
    fun sendHome() = sendConsumerControl(HidDescriptor.CONSUMER_AC_HOME)
    fun sendBack() = sendConsumerControl(HidDescriptor.CONSUMER_AC_BACK)

    /** 4 phím D-pad (lên/xuống/trái/phải) — chức năng như phím cứng điều hướng trên
     *  remote thật, gửi qua report bàn phím (usage mũi tên chuẩn) giống hệt 1 bàn
     *  phím vật lý cắm vào TV vẫn bấm được lên/xuống/trái/phải để duyệt menu. */
    fun sendDpadUp() = sendSpecialKey("UP")
    fun sendDpadDown() = sendSpecialKey("DOWN")
    fun sendDpadLeft() = sendSpecialKey("LEFT")
    fun sendDpadRight() = sendSpecialKey("RIGHT")
    /** OK/Chọn — dùng ENTER, đúng chuẩn khi điều hướng menu TV bằng bàn phím. */
    fun sendDpadOk() = sendSpecialKey("ENTER")

    /** Report Consumer Control giờ dài 2 byte (16 bit, xem HidDescriptor) nên bitmask
     *  cũng tách làm 2: byte0 = 8 bit thấp (các phím cũ), byte1 = bit cao nhất còn lại
     *  (hiện chỉ có Play/Pause ở bit thứ 9) — các hằng số CONSUMER_* vẫn giữ nguyên
     *  giá trị cũ (0x01..0x80) nên không phá vỡ gì, chỉ CONSUMER_PLAY_PAUSE = 0x100
     *  là rơi sang byte1. */
    private fun sendConsumerControl(bitmask: Int) {
        val device = connectedDevice ?: return
        val byte0 = (bitmask and 0xFF).toByte()
        val byte1 = ((bitmask shr 8) and 0xFF).toByte()
        // Nhấn xuống rồi nhả ra ngay qua keySender (có nghỉ nhỏ giữa 2 report) — cùng
        // lý do đã sửa cho bàn phím/chuột, tránh trường hợp hiếm gặp TV bỏ lỡ report
        // "nhả" khi 2 report tới quá sát nhau.
        safeExecuteKeySender {
            safeSendReport(device, HidDescriptor.ID_CONSUMER.toInt(), byteArrayOf(byte0, byte1))
            if (sleepPaced(KEY_HOLD_MS)) safeSendReport(device, HidDescriptor.ID_CONSUMER.toInt(), byteArrayOf(0, 0))
        }
    }

    // ---------- Gửi report bàn phím ----------

    /** true = tự chuyển tiếng Việt có dấu sang Telex trước khi gõ (mặc định bật). */
    var vietnameseTelexEnabled = true

    // Gửi report bàn phím qua 1 thread nền RIÊNG, xử lý tuần tự (FIFO) — thay vì bắn
    // hết report của cả chuỗi dán/gõ liên tiếp không nghỉ trên main thread. Bluetooth
    // HID bên nhận (TV) cần một khoảng nghỉ nhỏ giữa các report để nhận diện đúng
    // từng lần nhấn/nhả phím; gửi dồn dập không độ trễ là lý do TV rớt/lẫn chữ khi
    // dán đoạn văn bản dài (paste). Dùng 1 thread duy nhất để vẫn giữ đúng thứ tự
    // BACKSPACE rồi mới tới ký tự mới (xem SyncInputController.sendDiff).
    private val keySender: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HidKeySender").apply { isDaemon = true }
    }

    /** Gõ 1 chuỗi văn bản, gửi từng ký tự nối tiếp (chạy nền, có nghỉ giữa các phím). */
    fun typeText(text: String) {
        val normalized = KeyMapper.normalizePunctuation(text)
        val toSend = if (vietnameseTelexEnabled) VietnameseTelex.toTelex(normalized) else normalized
        safeExecuteKeySender {
            for (c in toSend) {
                val mapped = KeyMapper.charToKeycode(c) ?: continue
                sendKeyPressPaced(mapped.first, mapped.second)
            }
        }
    }

    fun sendSpecialKey(name: String) {
        val code = KeyMapper.SPECIAL[name] ?: return
        safeExecuteKeySender { sendKeyPressPaced(code, false) }
    }

    /** Đưa việc vào hàng đợi [keySender] — bọc try/catch vì ExecutorService đã bị
     *  shutdownNow() (xem unregister()) sẽ ném RejectedExecutionException nếu còn ai
     *  gọi vào sau đó; phòng hờ trường hợp hiếm 1 HidManager cũ vẫn còn được gọi tới
     *  đúng lúc đang bị thay bằng instance mới (xem MainActivity.resetHidManagerInstance). */
    private fun safeExecuteKeySender(action: () -> Unit) {
        try {
            keySender.execute(action)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "keySender đã đóng, bỏ qua report này", e)
        }
    }

    /** Gửi 1 lần nhấn+nhả phím, có nghỉ giữa các bước — PHẢI gọi từ [keySender] để giữ
     *  đúng thứ tự và không nghẽn main thread khi gõ/dán chuỗi dài. */
    private fun sendKeyPressPaced(keycode: Int, shift: Boolean) {
        val device = connectedDevice ?: return
        val modifier = if (shift) KeyMapper.MOD_SHIFT else 0
        // report: [modifier, reserved, key1..key6]
        val down = byteArrayOf(modifier.toByte(), 0, keycode.toByte(), 0, 0, 0, 0, 0)
        val up = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        safeSendReport(device, HidDescriptor.ID_KEYBOARD.toInt(), down)
        if (!sleepPaced(KEY_HOLD_MS)) return
        safeSendReport(device, HidDescriptor.ID_KEYBOARD.toInt(), up)
        sleepPaced(KEY_GAP_MS)
    }

    /** true nếu ngủ trọn vẹn, false nếu bị interrupt (vd app đang thoát) — dừng luôn
     *  phần còn lại thay vì cố gửi tiếp report dở dang. */
    private fun sleepPaced(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Gửi report an toàn: thiết bị có thể vừa mất kết nối/mất quyền giữa lúc gõ liên
     *  tục (vd đang gõ nguyên câu qua sendDiff) — không để 1 report lỗi làm văng app. */
    private fun safeSendReport(device: BluetoothDevice, id: Int, report: ByteArray) {
        try {
            hidDevice?.sendReport(device, id, report)
        } catch (e: Exception) {
            Log.w(TAG, "sendReport lỗi (bỏ qua): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HidManager"
        private const val PREF_LAST_DEVICE = "last_connected_device_address"

        // Khoảng nghỉ giữa lúc "nhấn" và "nhả" 1 phím, và giữa phím này với phím kế
        // tiếp. Giá trị nhỏ (mili-giây) nhưng đủ để TV không bị dồn report — tương tự
        // tốc độ gõ tay bình thường, không gây cảm giác chậm khi dán cả đoạn dài.
        private const val KEY_HOLD_MS = 8L
        private const val KEY_GAP_MS = 12L
    }
}
