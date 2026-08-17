package com.example.btremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

@SuppressLint("MissingPermission")
class HidManager(private val context: Context) {

    interface Listener {
        fun onRegistered()
        fun onUnregistered()
        fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean)
        fun onError(message: String)
    }

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
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
            connectedDevice = if (connected) device else null
            if (connected) {
                // Kết nối thành công -> hủy timeout đang chờ, tránh báo lỗi nhầm sau đó.
                pendingConnectRunnable?.let { timeoutHandler.removeCallbacks(it) }
                pendingConnectRunnable = null
            }
            if (connected && device != null) {
                // Nhớ lại thiết bị vừa kết nối thành công -> lần mở app sau tự kết nối
                // lại luôn, không cần vào "Chọn thiết bị" chọn lại từ đầu.
                prefs.edit().putString(PREF_LAST_DEVICE, device.address).apply()
            }
            listener?.onConnectionStateChanged(device, connected)
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
        cleanupRebondReceiver()
        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.w(TAG, "unregisterApp lỗi (bỏ qua vì đang thoát app)", e)
        }
    }

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var pendingConnectRunnable: Runnable? = null

    /** Thiết bị không phản hồi trong khoảng này sau khi gửi yêu cầu kết nối thì
     *  coi như thất bại — API HID không tự báo timeout, phải tự canh lấy. */
    private val CONNECT_TIMEOUT_MS = 8000L

    /** Yêu cầu kết nối tới 1 thiết bị đã pair (TV/PC) — gọi sau khi user chọn trong danh sách bonded devices. */
    fun connectTo(device: BluetoothDevice) {
        try {
            // connect() trả về true/false báo đã GỬI yêu cầu hay chưa, không phải
            // đã kết nối xong — trước đây bỏ qua giá trị này nên trường hợp gửi
            // thất bại ngay từ đầu (false) không hiện lỗi gì cả, tưởng đang chờ.
            val requestSent = hidDevice?.connect(device) ?: false
            if (!requestSent) {
                listener?.onError(
                    "Không gửi được yêu cầu kết nối tới ${safeDeviceLabel(device)} — " +
                        "thử bấm \"Đăng ký làm bàn phím và chuột\" lại rồi thử kết nối lại"
                )
                return
            }

            pendingConnectRunnable?.let { timeoutHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (connectedDevice?.address != device.address) {
                    listener?.onError(
                        "Không kết nối được tới ${safeDeviceLabel(device)} — thiết bị không phản hồi sau ${CONNECT_TIMEOUT_MS / 1000}s.\n\n" +
                            "Nguyên nhân thường gặp:\n" +
                            "• Thiết bị được pair TRƯỚC khi mở app này — cần gỡ pair (Forget) trên cả 2 máy rồi pair lại SAU khi app đã đăng ký HID xong.\n" +
                            "• TV/PC đang kết nối với chuột/bàn phím/remote Bluetooth khác — tắt Bluetooth thiết bị đó rồi thử lại."
                    )
                }
            }
            pendingConnectRunnable = runnable
            timeoutHandler.postDelayed(runnable, CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            listener?.onError("Không kết nối được tới thiết bị: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceLabel(device: BluetoothDevice): String =
        try { device.name ?: device.address } catch (e: SecurityException) { device.address }

    // ---------- Gỡ pair rồi tự pair lại (khi kết nối HID thất bại) ----------

    private var rebondReceiver: BroadcastReceiver? = null

    /**
     * Gỡ pair rồi tự pair lại 1 thiết bị — dùng khi kết nối HID thất bại vì
     * thiết bị được pair TRƯỚC khi app đăng ký HID (link key cũ không mang
     * thuộc tính HID, xem giải thích ở connectTo()/timeout ở trên).
     *
     * removeBond() là API ẩn của Android (không nằm trong SDK công khai) nên
     * phải gọi qua reflection — hoạt động trên phần lớn máy nhưng 1 số ROM
     * (custom ROM khóa kỹ) có thể chặn. Nếu vậy sẽ báo lỗi rõ ràng để người
     * dùng tự gỡ pair thủ công, thay vì im lặng thất bại.
     */
    @SuppressLint("MissingPermission")
    fun unpairAndReconnect(device: BluetoothDevice) {
        cleanupRebondReceiver()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val changedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changedDevice?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_NONE -> {
                        // Gỡ pair xong -> chủ động pair lại ngay, lúc này HID đã
                        // đăng ký sẵn nên link key mới sẽ có đúng thuộc tính HID.
                        try {
                            device.createBond()
                        } catch (e: Exception) {
                            cleanupRebondReceiver()
                            listener?.onError(
                                "Gỡ pair xong nhưng không pair lại được: ${e.message}. " +
                                    "Hãy vào Cài đặt Bluetooth pair thủ công với ${safeDeviceLabel(device)}."
                            )
                        }
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        cleanupRebondReceiver()
                        connectTo(device)
                    }
                    // BOND_BONDING: đang trong quá trình pair -> chờ tiếp, không làm gì.
                }
            }
        }
        rebondReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        try {
            // removeBond() ẩn -> gọi qua reflection.
            val removeBond = device.javaClass.getMethod("removeBond")
            val started = removeBond.invoke(device) as? Boolean ?: false
            if (!started) {
                cleanupRebondReceiver()
                listener?.onError(
                    "Không gỡ pair được ${safeDeviceLabel(device)} tự động — " +
                        "hãy vào Cài đặt Bluetooth gỡ pair thủ công rồi pair lại."
                )
            }
        } catch (e: Exception) {
            cleanupRebondReceiver()
            listener?.onError(
                "Máy này không cho app tự gỡ pair (${e.message}) — " +
                    "hãy vào Cài đặt Bluetooth gỡ pair \"${safeDeviceLabel(device)}\" thủ công rồi pair lại."
            )
        }
    }

    private fun cleanupRebondReceiver() {
        rebondReceiver?.let {
            try { context.unregisterReceiver(it) } catch (e: Exception) { /* đã unregister rồi, bỏ qua */ }
        }
        rebondReceiver = null
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

    private var mouseButtons = 0

    fun sendMouseMove(dx: Int, dy: Int) {
        sendMouseReport(mouseButtons, dx, dy, 0)
    }

    fun sendMouseClick(rightButton: Boolean) {
        val bit = if (rightButton) 0x02 else 0x01
        // nhấn xuống rồi nhả ra, giả lập 1 click hoàn chỉnh
        sendMouseReport(bit, 0, 0, 0)
        sendMouseReport(0, 0, 0, 0)
    }

    fun sendMouseScroll(wheel: Int) {
        sendMouseReport(mouseButtons, 0, 0, wheel)
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

    /** Report Consumer Control giờ dài 2 byte (16 bit, xem HidDescriptor) nên bitmask
     *  cũng tách làm 2: byte0 = 8 bit thấp (các phím cũ), byte1 = bit cao nhất còn lại
     *  (hiện chỉ có Play/Pause ở bit thứ 9) — các hằng số CONSUMER_* vẫn giữ nguyên
     *  giá trị cũ (0x01..0x80) nên không phá vỡ gì, chỉ CONSUMER_PLAY_PAUSE = 0x100
     *  là rơi sang byte1. */
    private fun sendConsumerControl(bitmask: Int) {
        val device = connectedDevice ?: return
        val byte0 = (bitmask and 0xFF).toByte()
        val byte1 = ((bitmask shr 8) and 0xFF).toByte()
        // Nhấn xuống rồi nhả ra ngay, giống cách gửi report bàn phím/chuột ở trên.
        safeSendReport(device, HidDescriptor.ID_CONSUMER.toInt(), byteArrayOf(byte0, byte1))
        safeSendReport(device, HidDescriptor.ID_CONSUMER.toInt(), byteArrayOf(0, 0))
    }

    // ---------- Gửi report bàn phím ----------

    /** true = tự chuyển tiếng Việt có dấu sang Telex trước khi gõ (mặc định bật). */
    var vietnameseTelexEnabled = true

    /** Gõ 1 chuỗi văn bản, gửi từng ký tự nối tiếp. */
    fun typeText(text: String) {
        val toSend = if (vietnameseTelexEnabled) VietnameseTelex.toTelex(text) else text
        for (c in toSend) {
            val mapped = KeyMapper.charToKeycode(c) ?: continue
            sendKeyPress(mapped.first, mapped.second)
        }
    }

    fun sendSpecialKey(name: String) {
        val code = KeyMapper.SPECIAL[name] ?: return
        sendKeyPress(code, false)
    }

    private fun sendKeyPress(keycode: Int, shift: Boolean) {
        val device = connectedDevice ?: return
        val modifier = if (shift) KeyMapper.MOD_SHIFT else 0
        // report: [modifier, reserved, key1..key6]
        val down = byteArrayOf(modifier.toByte(), 0, keycode.toByte(), 0, 0, 0, 0, 0)
        val up = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        safeSendReport(device, HidDescriptor.ID_KEYBOARD.toInt(), down)
        safeSendReport(device, HidDescriptor.ID_KEYBOARD.toInt(), up)
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
    }
}
