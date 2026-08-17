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
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

@SuppressLint("MissingPermission")
class HidManager(private val context: Context) {

    interface Listener {
        fun onRegistered()
        fun onUnregistered()
        fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean)
        fun onError(message: String)
        /** Máy nhận vừa báo Caps Lock đổi trạng thái (bật/tắt). Không bắt buộc lắng nghe. */
        fun onCapsLockChanged(capsLockOn: Boolean) {}
    }

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var registered: Boolean = false
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
            this@HidManager.registered = registered
            if (registered) listener?.onRegistered() else listener?.onUnregistered()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val connected = state == BluetoothProfile.STATE_CONNECTED
            connectedDevice = if (connected) device else null
            if (connected && device != null) {
                // Nhớ lại thiết bị vừa kết nối thành công -> lần mở app sau tự kết nối
                // lại luôn, không cần vào "Chọn thiết bị" chọn lại từ đầu.
                prefs.edit().putString(PREF_LAST_DEVICE, device.address).apply()
                // Đánh dấu thiết bị này đã từng kết nối HID OK ít nhất 1 lần -> từ
                // giờ về sau connectTo() sẽ không bao giờ tự hủy pair/pair lại với
                // riêng thiết bị này nữa (xem rememberKnownGoodDevice() + connectTo()).
                rememberKnownGoodDevice(device.address)
            } else {
                // Mất kết nối -> không còn biết chắc đèn Caps Lock máy nhận đang ở
                // trạng thái nào nữa, về lại mặc định "tắt" cho lần kết nối sau.
                capsLockOn = false
            }
            listener?.onConnectionStateChanged(device, connected)
        }

        /**
         * Host (TV/PC) chủ động gửi report này về mỗi khi trạng thái đèn LED
         * (Num/Caps/Scroll Lock) thay đổi — kể cả khi người dùng tự bấm Caps Lock
         * trên bàn phím thật khác, hoặc trạng thái đã bật sẵn từ trước khi app
         * kết nối tới. Nhờ đây HidManager luôn biết đúng trạng thái Caps Lock
         * thật của máy nhận, không cần đoán hay để người dùng tự đảo thủ công.
         */
        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            if (id != HidDescriptor.ID_KEYBOARD || data == null || data.isEmpty()) return
            val newCapsLockOn = (data[0].toInt() and 0x02) != 0
            if (newCapsLockOn != capsLockOn) {
                capsLockOn = newCapsLockOn
                listener?.onCapsLockChanged(capsLockOn)
            }
        }
    }

    /** Trạng thái Caps Lock thật của máy nhận, do chính máy nhận báo về qua HID
     *  Output Report (xem onSetReport ở trên) — không phải suy đoán. */
    private var capsLockOn = false

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
        mainHandler.removeCallbacksAndMessages(null)
        cleanupBondReceiver()
        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.w(TAG, "unregisterApp lỗi (bỏ qua vì đang thoát app)", e)
        }
    }

    /**
     * Yêu cầu kết nối tới 1 thiết bị đã pair (TV/PC) — gọi sau khi user chọn trong
     * danh sách bonded devices, hoặc từ autoReconnectLastDevice().
     *
     * Vấn đề thực tế: nếu thiết bị đích trước đó đã từng pair Bluetooth THƯỜNG với
     * điện thoại này (vd nhận diện là "điện thoại" để nghe gọi/phát nhạc, không
     * phải để làm bàn phím/chuột HID), 1 số máy sẽ từ chối kết nối HID vì cặp pair
     * cũ không đúng "vai trò". Cách xử lý: sau 1 khoảng chờ, nếu vẫn chưa kết nối
     * được, TỰ ÂM THẦM hủy pair rồi pair lại rồi kết nối lại đúng 1 lần — không hỏi,
     * không toast, không thông báo gì cho người dùng (xem maybeAutoRepair bên dưới).
     *
     * QUAN TRỌNG: cơ chế tự hủy pair/pair lại này CHỈ áp dụng cho lần đầu gặp lỗi
     * thật sự với 1 thiết bị — tức thiết bị đó CHƯA từng kết nối HID thành công
     * lần nào (xem isKnownGoodDevice()). Thiết bị nào đã từng kết nối HID OK rồi
     * thì những lần sau chỉ bấm chọn là kết nối thẳng qua doConnect(), không đụng
     * gì tới pairing nữa — tránh việc 1 lần host phản hồi chậm hơn bình thường
     * (quá CONNECT_CHECK_DELAY_MS) lại khiến app hủy pair 1 kết nối vốn đang tốt.
     */
    fun connectTo(device: BluetoothDevice) {
        // Mỗi lần user/hệ thống chủ động gọi kết nối tới thiết bị này là 1 "lượt"
        // mới -> cho phép thử auto re-pair lại từ đầu (guard chỉ chặn LẶP trong
        // cùng 1 lượt, không chặn giữa các lượt khác nhau).
        if (autoRepairTriedForAddress == device.address) autoRepairTriedForAddress = null
        doConnect(device)
        if (!isKnownGoodDevice(device.address)) {
            mainHandler.postDelayed({ maybeAutoRepair(device) }, CONNECT_CHECK_DELAY_MS)
        }
    }

    private fun doConnect(device: BluetoothDevice) {
        try {
            hidDevice?.connect(device)
        } catch (e: Exception) {
            // Không gọi listener?.onError ở đây: lỗi ở bước này có thể tự phục hồi
            // qua auto re-pair bên dưới, báo lỗi ngay sẽ gây thông báo giả (false
            // alarm) trong lúc app đang tự xử lý.
            Log.w(TAG, "connect() lỗi (bỏ qua, chờ auto re-pair tự xử lý): ${e.message}")
        }
    }

    private var autoRepairTriedForAddress: String? = null
    private var bondReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun maybeAutoRepair(device: BluetoothDevice) {
        if (connectedDevice?.address == device.address) return // đã kết nối được rồi
        if (autoRepairTriedForAddress == device.address) return // đã thử với thiết bị này trong lượt này rồi
        autoRepairTriedForAddress = device.address
        silentUnpairAndRepair(device)
    }

    /** Hủy pair -> đợi hệ thống báo hủy xong -> pair lại -> đợi pair xong -> kết
     *  nối HID lại. Toàn bộ diễn ra im lặng, không có UI/toast nào cho người dùng. */
    @SuppressLint("MissingPermission")
    private fun silentUnpairAndRepair(device: BluetoothDevice) {
        cleanupBondReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                val changed: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (changed?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_NONE -> {
                        // Vừa hủy pair xong -> pair lại ngay.
                        try {
                            device.createBond()
                        } catch (e: Exception) {
                            Log.w(TAG, "createBond() lỗi (bỏ qua): ${e.message}")
                            cleanupBondReceiver()
                        }
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        // Pair lại xong -> thử kết nối HID lại đúng 1 lần nữa.
                        cleanupBondReceiver()
                        doConnect(device)
                    }
                }
            }
        }
        bondReceiver = receiver
        try {
            // Android 13+ (targetSdk 34) bắt buộc chỉ rõ cờ exported khi
            // registerReceiver() bằng code, không thì ném SecurityException lúc
            // chạy. Đây là broadcast hệ thống (đổi trạng thái pair), không cần
            // app khác gửi được tới nên dùng NOT_EXPORTED là đúng và an toàn.
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver lỗi (bỏ qua): ${e.message}")
            bondReceiver = null
            return
        }
        // Phòng khi quá trình hủy pair/pair lại bị kẹt (không có phản hồi nào từ hệ
        // thống) -> tự dọn dẹp receiver sau 1 khoảng đủ dài, vẫn hoàn toàn im lặng.
        mainHandler.postDelayed({ cleanupBondReceiver() }, REPAIR_TIMEOUT_MS)
        removeBondReflect(device)
    }

    private fun cleanupBondReceiver() {
        val r = bondReceiver ?: return
        bondReceiver = null
        try {
            context.unregisterReceiver(r)
        } catch (e: Exception) {
            // Đã tự hủy đăng ký hoặc chưa từng đăng ký -> bỏ qua.
        }
    }

    /** BluetoothDevice.removeBond() không nằm trong SDK public — gọi qua reflection,
     *  cách làm phổ biến và ổn định qua nhiều đời Android cho việc hủy pair thủ công. */
    @SuppressLint("MissingPermission")
    private fun removeBondReflect(device: BluetoothDevice) {
        try {
            device.javaClass.getMethod("removeBond").invoke(device)
        } catch (e: Exception) {
            Log.w(TAG, "removeBond() lỗi (bỏ qua): ${e.message}")
            cleanupBondReceiver()
        }
    }

    /** Thiết bị này đã từng kết nối HID thành công ít nhất 1 lần chưa? */
    private fun isKnownGoodDevice(address: String): Boolean =
        prefs.getStringSet(PREF_HID_OK_DEVICES, null)?.contains(address) == true

    /** Đánh dấu 1 địa chỉ vào danh sách "đã từng kết nối HID OK". SharedPreferences
     *  khuyến cáo không sửa trực tiếp Set trả về từ getStringSet() -> copy ra
     *  HashSet mới rồi ghi đè lại toàn bộ. */
    private fun rememberKnownGoodDevice(address: String) {
        val current = prefs.getStringSet(PREF_HID_OK_DEVICES, null) ?: emptySet()
        if (address in current) return
        prefs.edit()
            .putStringSet(PREF_HID_OK_DEVICES, HashSet(current).apply { add(address) })
            .apply()
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

    /**
     * Gõ 1 chuỗi văn bản, gửi từng ký tự nối tiếp.
     *
     * Tự bù Caps Lock: KeyMapper.charToKeycode() tính sẵn cờ Shift theo giả định
     * Caps Lock máy nhận đang TẮT. Nếu capsLockOn (đọc thật từ máy nhận, xem
     * onSetReport) đang bật, chỉ riêng CHỮ CÁI mới bị đảo hoa/thường trên bàn
     * phím thật — số và ký hiệu (!, @, .,...) không bị Caps Lock ảnh hưởng — nên
     * ở đây chỉ đảo bit Shift cho chữ cái, giữ nguyên cho các ký tự còn lại.
     */
    fun typeText(text: String) {
        val toSend = if (vietnameseTelexEnabled) VietnameseTelex.toTelex(text) else text
        for (c in toSend) {
            val mapped = KeyMapper.charToKeycode(c) ?: continue
            val shift = if (c.isLetter() && capsLockOn) !mapped.second else mapped.second
            sendKeyPress(mapped.first, shift)
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
        // Tập hợp địa chỉ các thiết bị đã từng kết nối HID thành công ít nhất 1 lần.
        private const val PREF_HID_OK_DEVICES = "hid_known_good_device_addresses"
        // Chờ 4s sau connect() trước khi kết luận "chưa thấy kết nối" và cân nhắc
        // auto re-pair — đủ thời gian cho 1 kết nối HID bình thường thành công.
        private const val CONNECT_CHECK_DELAY_MS = 4000L
        // Nếu hủy pair/pair lại không có phản hồi gì sau 15s (kẹt) thì tự dọn dẹp,
        // tránh giữ BroadcastReceiver mãi mãi.
        private const val REPAIR_TIMEOUT_MS = 15000L
    }
}
