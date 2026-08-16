package com.example.btremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Giao diện tối giản: CHỈ còn trackpad, không còn nút nào khác (đã bỏ Home/
 * Back/Volume/Media/Mic/Bàn phím/Đăng ký HID/Chọn thiết bị/2 nút toàn màn
 * hình góc trên). App tự lo hết:
 *  - Mở app -> tự xin quyền Bluetooth (nếu chưa có) -> tự bật Bluetooth (nếu
 *    đang tắt) -> tự đăng ký làm thiết bị HID (bàn phím + chuột).
 *  - Đăng ký xong -> tự kết nối lại thiết bị đã dùng lần gần nhất (nếu còn
 *    pair), không cần chọn lại (xem HidManager.autoReconnectLastDevice()).
 *  - Lần đầu dùng: vẫn phải vào Cài đặt Bluetooth của TV/PC để pair với
 *    "Remote TV Bluetooth" như pair chuột Bluetooth thường (xem README) —
 *    việc này không thể tự động hoá được vì không còn nút "Chọn thiết bị".
 */
class MainActivity : AppCompatActivity() {

    private lateinit var hidManager: HidManager
    private lateinit var trackpad: TrackpadView

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

    private val startupPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            ensureBluetoothEnabledThenRegister()
        } else {
            Toast.makeText(this, "Cần cấp quyền Bluetooth để hoạt động", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            hidManager.start()
        } else {
            Toast.makeText(
                this,
                "Cần bật Bluetooth trước thì mới đăng ký làm bàn phím + chuột được",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        trackpad = findViewById(R.id.trackpad)

        hidManager = HidManager(this)
        hidManager.listener = object : HidManager.Listener {
            override fun onRegistered() {
                runOnUiThread {
                    hidManager.autoReconnectLastDevice()
                }
            }

            override fun onUnregistered() {}

            override fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean) {
                runOnUiThread {
                    if (connected) {
                        Toast.makeText(
                            this@MainActivity, "Đã kết nối: ${safeName(device)}", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        trackpad.onMove = { dx, dy -> hidManager.sendMouseMove(dx, dy) }
        trackpad.onClick = { rightButton -> hidManager.sendMouseClick(rightButton) }
        trackpad.onScroll = { dy -> hidManager.sendMouseScroll(dy) }

        autoRegisterAndPromptBluetooth()
    }

    /** Tự đăng ký HID (bàn phím + chuột) ngay khi mở app: xin quyền trước nếu chưa có,
     *  rồi ĐẢM BẢO Bluetooth đã bật trước khi đăng ký (xem ensureBluetoothEnabledThenRegister). */
    private fun autoRegisterAndPromptBluetooth() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ensureBluetoothEnabledThenRegister()
        } else {
            startupPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureBluetoothEnabledThenRegister() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Thiết bị này không có Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (adapter.isEnabled) {
            hidManager.start()
        } else {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice?): String {
        if (device == null) return "?"
        return try { device.name ?: device.address } catch (e: SecurityException) { device.address }
    }

    override fun onDestroy() {
        hidManager.unregister()
        super.onDestroy()
    }
}
