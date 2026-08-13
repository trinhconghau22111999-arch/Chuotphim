package com.example.btremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var hidManager: HidManager
    private lateinit var statusText: TextView
    private lateinit var trackpad: TrackpadView
    private lateinit var hiddenInput: EditText

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

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            hidManager.start()
        } else {
            Toast.makeText(this, "Cần cấp quyền Bluetooth để hoạt động", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        trackpad = findViewById(R.id.trackpad)
        hiddenInput = findViewById(R.id.hiddenInput)

        hidManager = HidManager(this)
        hidManager.listener = object : HidManager.Listener {
            override fun onRegistered() {
                runOnUiThread { statusText.text = "Đã đăng ký HID — hãy chọn thiết bị để kết nối" }
            }

            override fun onUnregistered() {
                runOnUiThread { statusText.text = "Đã huỷ đăng ký HID" }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean) {
                runOnUiThread {
                    statusText.text = if (connected)
                        "Đã kết nối: ${safeName(device)}"
                    else
                        "Đã đăng ký HID — chưa kết nối thiết bị nào"
                }
            }

            override fun onError(message: String) {
                runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
            }
        }

        findViewById<Button>(R.id.btnRegister).setOnClickListener { ensurePermissionsThenStart() }
        findViewById<Button>(R.id.btnPickDevice).setOnClickListener { showBondedDevicesDialog() }
        findViewById<android.widget.CheckBox>(R.id.chkTelex).setOnCheckedChangeListener { _, checked ->
            hidManager.vietnameseTelexEnabled = checked
        }

        findViewById<Button>(R.id.btnOpenKeyboard).setOnClickListener {
            hiddenInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(hiddenInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        // Tắt gợi ý/autocorrect/composing để bàn phím ảo (Gboard...) không tự chèn lại
        // ký tự đang "composing" mỗi khi ta clear() nội dung bên dưới.
        hiddenInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        lateinit var textWatcher: android.text.TextWatcher
        textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString() ?: return
                if (text.isNotEmpty()) {
                    hidManager.typeText(text)
                    // QUAN TRỌNG: gỡ listener trước khi clear() rồi gắn lại ngay sau.
                    // Nếu không, việc sửa Editable ngay trong afterTextChanged sẽ khiến
                    // IME (đặc biệt Gboard khi đang composing/gợi ý tiếng Việt) chèn lại
                    // ký tự vừa gõ, gây ra hiện tượng ký tự lặp liên tục không dừng.
                    hiddenInput.removeTextChangedListener(textWatcher)
                    s.clear()
                    hiddenInput.addTextChangedListener(textWatcher)
                }
            }
        }
        hiddenInput.addTextChangedListener(textWatcher)

        // Bắt phím Backspace/Enter thật từ bàn phím ảo (không chỉ ký tự thường)
        hiddenInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DEL -> { hidManager.sendSpecialKey("BACKSPACE"); true }
                    android.view.KeyEvent.KEYCODE_ENTER -> { hidManager.sendSpecialKey("ENTER"); true }
                    else -> false
                }
            } else false
        }

        findViewById<Button>(R.id.keyBackspace).setOnClickListener { hidManager.sendSpecialKey("BACKSPACE") }
        findViewById<Button>(R.id.keyEnter).setOnClickListener { hidManager.sendSpecialKey("ENTER") }
        findViewById<Button>(R.id.keyTab).setOnClickListener { hidManager.sendSpecialKey("TAB") }
        findViewById<Button>(R.id.keyLeft).setOnClickListener { hidManager.sendSpecialKey("LEFT") }
        findViewById<Button>(R.id.keyRight).setOnClickListener { hidManager.sendSpecialKey("RIGHT") }
        findViewById<Button>(R.id.keyUp).setOnClickListener { hidManager.sendSpecialKey("UP") }
        findViewById<Button>(R.id.keyDown).setOnClickListener { hidManager.sendSpecialKey("DOWN") }
        findViewById<Button>(R.id.keyHome).setOnClickListener { hidManager.sendSpecialKey("HOME") }
        findViewById<Button>(R.id.keyEsc).setOnClickListener { hidManager.sendSpecialKey("ESC") }

        trackpad.onMove = { dx, dy -> hidManager.sendMouseMove(dx, dy) }
        trackpad.onClick = { rightButton -> hidManager.sendMouseClick(rightButton) }
        trackpad.onScroll = { dy -> hidManager.sendMouseScroll(dy) }
    }

    private fun ensurePermissionsThenStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            hidManager.start()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBondedDevicesDialog() {
        val devices = hidManager.bondedDevices().toList()
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "Chưa pair với thiết bị nào. Vào Cài đặt > Bluetooth, pair với TV/PC trước.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val names = devices.map { safeName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chọn thiết bị để kết nối")
            .setItems(names) { _, which -> hidManager.connectTo(devices[which]) }
            .show()
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
