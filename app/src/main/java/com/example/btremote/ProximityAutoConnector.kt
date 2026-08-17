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
 * Tự động kết nối thiết bị đã pair gần nhất dựa trên tín hiệu RSSI.
 *
 * Cách hoạt động:
 * - Cứ mỗi [SCAN_INTERVAL_MS] ms, gọi startDiscovery() để quét RSSI các thiết bị lân cận
 * - Sau mỗi lần quét, so sánh RSSI của các thiết bị đã pair tìm thấy
 * - Nếu thiết bị gần nhất KHÁC thiết bị đang kết nối → tự switch
 * - Chỉ switch khi chênh lệch RSSI >= [RSSI_SWITCH_THRESHOLD] dBm (tránh flip-flop liên tục)
 */
@SuppressLint("MissingPermission")
class ProximityAutoConnector(
    private val context: Context,
    private val hidManager: HidManager,
    private val onAutoSwitch: (newDevice: BluetoothDevice, oldDevice: BluetoothDevice?) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val rssiMap = mutableMapOf<String, Int>() // address -> RSSI
    private var isRunning = false
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
                    evaluateAndSwitch()
                    // Lên lịch quét tiếp theo
                    if (isRunning) handler.postDelayed(::startScan, SCAN_INTERVAL_MS)
                }
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(scanReceiver, filter)
        isReceiverRegistered = true
        startScan()
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        if (isReceiverRegistered) {
            try { context.unregisterReceiver(scanReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}
    }

    private fun startScan() {
        rssiMap.clear()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        adapter.startDiscovery()
    }

    private fun evaluateAndSwitch() {
        val bonded = hidManager.bondedDevices()
        if (bonded.size <= 1) return // Chỉ 1 thiết bị pair thì không cần auto-switch

        // Lọc chỉ lấy thiết bị đã pair có tín hiệu tìm thấy trong lần quét này
        val candidates = bonded
            .mapNotNull { device -> rssiMap[device.address]?.let { rssi -> device to rssi } }
            .sortedByDescending { it.second } // sắp xếp RSSI cao nhất (gần nhất) trước

        if (candidates.isEmpty()) return

        val (nearest, nearestRssi) = candidates.first()
        val currentAddress = hidManager.currentConnectedAddress

        // Nếu thiết bị gần nhất đã là thiết bị đang kết nối → không làm gì
        if (nearest.address == currentAddress) return

        // Kiểm tra chênh lệch RSSI với thiết bị hiện tại
        val currentRssi = currentAddress?.let { rssiMap[it] }
        if (currentRssi != null && (nearestRssi - currentRssi) < RSSI_SWITCH_THRESHOLD) return

        // Switch sang thiết bị gần hơn
        val oldDevice = bonded.firstOrNull { it.address == currentAddress }
        Log.d(TAG, "Auto-switch: ${oldDevice?.name} (${currentRssi}dBm) → ${nearest.name} (${nearestRssi}dBm)")
        hidManager.connectTo(nearest)
        onAutoSwitch(nearest, oldDevice)
    }

    companion object {
        private const val TAG = "ProximityAutoConnector"
        private const val SCAN_INTERVAL_MS = 15_000L  // quét mỗi 15 giây
        private const val RSSI_SWITCH_THRESHOLD = 10   // chỉ switch khi gần hơn ít nhất 10 dBm
    }
}
