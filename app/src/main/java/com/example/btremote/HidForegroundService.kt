package com.example.btremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder

/**
 * Giữ HidManager sống trong 1 service riêng, TÁCH KHỎI vòng đời MainActivity.
 *
 * Lý do cần cái này: BluetoothHidDevice.registerApp() gắn với process, không
 * phải thứ hệ điều hành tự nhớ giữa các lần mở app. Trước đây HidManager sống
 * ngay trong Activity — mỗi khi Android dọn dẹp Activity ở nền (thu nhỏ lâu,
 * thiếu RAM, vuốt app khỏi recent...) thì mở lại app phải registerApp() lại
 * từ đầu, cảm giác như app "lâu lâu lại bắt đăng ký lại".
 *
 * Đưa HidManager vào 1 foreground service (có thông báo cố định để hệ thống
 * không dọn dẹp) giúp việc đăng ký + kết nối thiết bị sống độc lập, tồn tại
 * lâu hơn nhiều so với vòng đời Activity. MainActivity giờ chỉ "bind" (nối)
 * vào service đang chạy sẵn để lấy đúng trạng thái hiện tại — nếu đã đăng ký/
 * đã kết nối từ trước thì hiện luôn, không phải chờ đăng ký lại.
 */
class HidForegroundService : Service() {

    inner class LocalBinder : Binder() {
        val service: HidForegroundService get() = this@HidForegroundService
    }

    val hidManager: HidManager by lazy { HidManager(applicationContext) }

    /** Listener của MainActivity (khi có màn hình đang mở) — service luôn tự
     *  xử lý phần thông báo/trạng thái của chính nó trước, rồi mới chuyển tiếp
     *  sự kiện cho listener ngoài này nếu có, nên không mất tính năng gì khi
     *  MainActivity gán listener riêng của nó. */
    var externalListener: HidManager.Listener? = null

    private val binder = LocalBinder()

    private val internalListener = object : HidManager.Listener {
        override fun onRegistered() {
            hidManager.autoReconnectLastDevice()
            updateNotification("Đã sẵn sàng — chưa kết nối thiết bị nào")
            externalListener?.onRegistered()
        }

        override fun onUnregistered() {
            updateNotification("Chưa đăng ký làm bàn phím/chuột")
            externalListener?.onUnregistered()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean) {
            updateNotification(
                if (connected) "Đang kết nối tới ${safeDeviceName(device)}"
                else "Đã đăng ký — chưa kết nối thiết bị nào"
            )
            externalListener?.onConnectionStateChanged(device, connected)
        }

        override fun onError(message: String) {
            updateNotification("Lỗi: $message")
            externalListener?.onError(message)
        }

        override fun onCapsLockChanged(capsLockOn: Boolean) {
            externalListener?.onCapsLockChanged(capsLockOn)
        }
    }

    override fun onCreate() {
        super.onCreate()
        hidManager.listener = internalListener
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Đang chờ đăng ký..."))
        // Thử tự đăng ký ngay khi service khởi động — chỉ thành công nếu quyền
        // Bluetooth đã được cấp từ trước (các lần mở app sau); nếu chưa,
        // MainActivity sẽ tự xin quyền rồi gọi hidManager.start() lại như cũ.
        hidManager.start()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        hidManager.unregister()
        super.onDestroy()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice?): String {
        if (device == null) return "?"
        return try { device.name ?: device.address } catch (e: SecurityException) { device.address }
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BT Remote đang chạy nền")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "BT Remote — trạng thái kết nối",
                NotificationManager.IMPORTANCE_LOW // im lặng, không kêu/rung
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "bt_remote_hid_service"
        private const val NOTIFICATION_ID = 1001
    }
}
