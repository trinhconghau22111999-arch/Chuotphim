package com.example.btremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Xử lý khi thiết bị đang kết nối bị RỚT ngoài ý muốn (ra xa/mất tín hiệu qua Bluetooth).
 *
 * KHÁC bản cũ: bộ này KHÔNG còn tự "cướp" kết nối chỉ vì có thiết bị khác tín hiệu
 * mạnh hơn trong khi vẫn đang kết nối ổn định — chỉ can thiệp sau khi kết nối hiện
 * tại đã thật sự bị ngắt.
 *
 * Luồng xử lý:
 * 1. [onDisconnected] được gọi khi 1 thiết bị vừa bị ngắt ngoài ý muốn → "kích hoạt"
 *    (arm) bộ này cho đúng thiết bị đó.
 * 2. Thử kết nối lại trực tiếp với chính thiết bị vừa rớt [RETRY_COUNT] lần, cách
 *    nhau [RETRY_INTERVAL_MS] ms.
 * 3. Nếu vẫn không kết nối lại được (ra xa quá, không nối lại được) → bắt đầu quét
 *    RSSI các thiết bị đã pair khác, tìm thiết bị gần nhất còn trong tầm và tự kết
 *    nối tạm sang đó (fallback).
 * 4. Trong lúc dùng thiết bị fallback, tiếp tục quét nền định kỳ. Ngay khi thiết bị
 *    gốc xuất hiện lại trong 1 lần quét → ưu tiên thử nối lại nó trước.
 * 5. Nối lại thiết bị gốc thành công → toàn bộ tính năng này TẮT NGAY (loại bỏ),
 *    quay về trạng thái bình thường cho tới lần rớt kết nối tiếp theo.
 * 6. Nếu người dùng tự tay chọn thiết bị khác giữa chừng → gọi [disarm] để huỷ, tôn
 *    trọng lựa chọn thủ công thay vì tự động can thiệp tiếp.
 */
@SuppressLint("MissingPermission")
class ProximityAutoConnector(
    private val context: Context,
    private val hidManager: HidManager,
    private val onFallbackConnected: (newDevice: BluetoothDevice, oldDevice: BluetoothDevice) -> Unit,
    private val onRestoredOriginal: (device: BluetoothDevice) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val rssiMap = mutableMapOf<String, Int>() // address -> RSSI của lần quét gần nhất

    private var armed = false                        // đang trong tiến trình xử lý rớt kết nối
    private var originalDevice: BluetoothDevice? = null // thiết bị vừa bị rớt, cần ưu tiên nối lại
    private var retriesLeft = 0
    private var isScanning = false
    private var isReceiverRegistered = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    rssiMap[device.address] = rssi
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false
                    if (armed) evaluateAfterScan()
                }
            }
        }
    }

    /** Gọi khi [device] vừa bị ngắt kết nối NGOÀI Ý MUỐN (không phải do người dùng
     *  chủ động chọn thiết bị khác). Kích hoạt quy trình tìm cách khôi phục kết nối. */
    fun onDisconnected(device: BluetoothDevice) {
        if (hidManager.bondedDevices().size <= 1) return // chỉ 1 thiết bị pair thì không có gì để fallback sang
        if (armed) disarm() // đang xử lý dở thiết bị khác thì huỷ, ưu tiên thiết bị vừa rớt gần nhất
        armed = true
        originalDevice = device
        retriesLeft = RETRY_COUNT
        ensureReceiverRegistered()
        Log.d(TAG, "Rớt kết nối ${safeName(device)} — thử nối lại trực tiếp trước")
        handler.postDelayed(::attemptDirectReconnect, RETRY_INTERVAL_MS)
    }

    /** Huỷ toàn bộ tiến trình fallback đang chạy (nếu có). Gọi khi: user tự tay chọn
     *  thiết bị khác, hoặc khi đã khôi phục thành công về thiết bị gốc. */
    fun disarm() {
        armed = false
        originalDevice = null
        handler.removeCallbacksAndMessages(null)
        if (isScanning) {
            try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}
            isScanning = false
        }
        if (isReceiverRegistered) {
            try { context.unregisterReceiver(scanReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }

    /** Alias để tương thích chỗ gọi cũ (onDestroy) — huỷ hẳn khi app đóng. */
    fun stop() = disarm()

    private fun ensureReceiverRegistered() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(scanReceiver, filter)
        isReceiverRegistered = true
    }

    private fun attemptDirectReconnect() {
        if (!armed) return
        val target = originalDevice ?: return

        // Có thể hệ thống đã tự nối lại rồi (ngoài luồng này) → coi như xong việc.
        if (hidManager.currentConnectedAddress == target.address) {
            onRestoredOriginal(target)
            disarm()
            return
        }

        if (retriesLeft > 0) {
            retriesLeft--
            Log.d(TAG, "Thử nối lại ${safeName(target)} — còn $retriesLeft lần thử")
            hidManager.connectTo(target)
            handler.postDelayed(::attemptDirectReconnect, RETRY_INTERVAL_MS)
            return
        }

        // Hết số lần thử trực tiếp mà vẫn không nối lại được (đúng như bạn mô tả:
        // "vừa kết nối xong bị ngắt do xa qua bluetooth không kết nối lại được")
        // → mới bắt đầu tìm thiết bị đã pair gần nhất khác để dùng tạm.
        Log.d(TAG, "Không nối lại được ${safeName(target)} sau $RETRY_COUNT lần — quét tìm thiết bị gần nhất khác")
        startFallbackScan()
    }

    private fun startFallbackScan() {
        if (!armed) return
        rssiMap.clear()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        isScanning = true
        adapter.startDiscovery()
    }

    private fun evaluateAfterScan() {
        if (!armed) return
        val target = originalDevice ?: return
        val bonded = hidManager.bondedDevices()

        // Thiết bị gốc đã đọc được tín hiệu trở lại trong lần quét này → ưu tiên thử
        // nối lại nó trước, thay vì dùng tiếp thiết bị fallback.
        if (rssiMap.containsKey(target.address)) {
            retriesLeft = RETRY_COUNT
            handler.postDelayed(::attemptDirectReconnect, RETRY_INTERVAL_MS)
            return
        }

        // Tìm thiết bị đã pair khác (không phải thiết bị gốc) có tín hiệu gần nhất,
        // và chỉ chuyển nếu chưa đang kết nối sẵn với đúng thiết bị đó.
        val candidates = bonded
            .filter { it.address != target.address }
            .mapNotNull { device -> rssiMap[device.address]?.let { rssi -> device to rssi } }
            .sortedByDescending { it.second }

        if (candidates.isNotEmpty() && hidManager.currentConnectedAddress != candidates.first().first.address) {
            val (nearest, rssi) = candidates.first()
            Log.d(TAG, "Fallback sang thiết bị gần nhất: ${safeName(nearest)} (${rssi}dBm)")
            hidManager.connectTo(nearest)
            onFallbackConnected(nearest, target)
        }

        // Vẫn tiếp tục quét nền định kỳ để phát hiện khi thiết bị gốc quay lại tầm.
        handler.postDelayed(::startFallbackScan, SCAN_INTERVAL_MS)
    }

    private fun safeName(device: BluetoothDevice): String =
        try { device.name ?: device.address } catch (_: Exception) { device.address }

    companion object {
        private const val TAG = "ProximityAutoConnector"
        private const val RETRY_COUNT = 3
        private const val RETRY_INTERVAL_MS = 4_000L
        private const val SCAN_INTERVAL_MS = 15_000L
    }
}
