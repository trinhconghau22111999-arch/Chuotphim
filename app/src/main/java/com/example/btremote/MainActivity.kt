package com.example.btremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var hidManager: HidManager
    private lateinit var topBar: android.widget.FrameLayout
    private lateinit var statusText: TextView
    private lateinit var btnRegisterHid: com.google.android.material.button.MaterialButton
    private lateinit var trackpad: TrackpadView
    private lateinit var hiddenInput: EditText

    // Các thành phần dùng để tự sắp xếp lại layout (chuột / 3 phím / bàn phím ảo)
    private lateinit var mainColumn: LinearLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var divider: android.view.View
    private var isKeyboardVisible = false

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

    // Launcher xin quyền lúc mở app: xin quyền xong thì kiểm tra/bật Bluetooth
    // trước, chỉ đăng ký HID (bàn phím + chuột) SAU KHI Bluetooth đã bật.
    private val startupPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            ensureBluetoothEnabledThenRegister()
        } else {
            resetRegisterButton()
            Toast.makeText(this, "Cần cấp quyền Bluetooth để hoạt động", Toast.LENGTH_LONG).show()
        }
    }

    // Launcher mở hộp thoại hệ thống "Cho phép Remote TV Bluetooth bật Bluetooth?".
    // Chỉ khi người dùng ĐỒNG Ý bật thì mới tiến hành đăng ký HID ngay sau đó.
    private val enableBluetoothLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            hidManager.start()
        } else {
            resetRegisterButton()
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

        topBar = findViewById(R.id.topBar)
        statusText = findViewById(R.id.statusText)
        btnRegisterHid = findViewById(R.id.btnRegisterHid)
        trackpad = findViewById(R.id.trackpad)
        hiddenInput = findViewById(R.id.hiddenInput)
        mainColumn = findViewById(R.id.mainColumn)
        controlsRow = findViewById(R.id.controlsRow)
        divider = findViewById(R.id.divider)

        // Ban đầu: chưa đăng ký HID -> hiện nút, ẩn dòng trạng thái.
        setHidRegisteredUi(registered = false)
        btnRegisterHid.setOnClickListener {
            btnRegisterHid.isEnabled = false
            btnRegisterHid.text = "Đang đăng ký..."
            autoRegisterAndPromptBluetooth()
        }

        hidManager = HidManager(this)
        hidManager.listener = object : HidManager.Listener {
            override fun onRegistered() {
                runOnUiThread {
                    setHidRegisteredUi(registered = true)
                    statusText.text = "Đã đăng ký HID — hãy chọn thiết bị để kết nối"
                }
            }

            override fun onUnregistered() {
                runOnUiThread { setHidRegisteredUi(registered = false) }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean) {
                runOnUiThread {
                    setHidRegisteredUi(registered = true)
                    statusText.text = if (connected)
                        "Đã kết nối: ${safeName(device)}"
                    else
                        "Đã đăng ký HID — chưa kết nối thiết bị nào"
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    // Đăng ký thất bại (Bluetooth tắt, thiếu quyền...) -> cho bấm lại nút.
                    resetRegisterButton()
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btnPickDevice).setOnClickListener { showBondedDevicesDialog() }
        // Checkbox Telex đã bị ẩn khỏi giao diện — luôn dùng Telex mặc định (đã true sẵn trong HidManager).
        // Nếu ký tự nào không map được (không hỗ trợ), KeyMapper sẽ tự bỏ qua ký tự đó (xem typeText()).

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

        findViewById<Button>(R.id.keyHome).setOnClickListener { hidManager.sendSpecialKey("HOME") }

        trackpad.onMove = { dx, dy -> hidManager.sendMouseMove(dx, dy) }
        trackpad.onClick = { rightButton -> hidManager.sendMouseClick(rightButton) }
        trackpad.onScroll = { dy -> hidManager.sendMouseScroll(dy) }

        setupAutoLayout()

        // Mở app là tự kiểm tra/bật Bluetooth trước (xin quyền nếu cần), CHỈ SAU KHI
        // Bluetooth đã bật mới tự đăng ký làm bàn phím + chuột Bluetooth.
        autoRegisterAndPromptBluetooth()
    }

    /**
     * Theo dõi việc bàn phím ảo hệ thống hiện/ẩn để tự canh chỉnh lại layout:
     *  - Bàn phím ẩn: hạ 3 phím (controlsRow) xuống dưới cùng, trackpad chiếm gần
     *    hết màn hình -> diện tích chuột lớn nhất.
     *  - Bàn phím hiện: đưa 3 phím lên trên (ngay dưới statusText), trackpad nằm
     *    giữa 3 phím và bàn phím. Vì trackpad dùng layout_weight="1" trong phần
     *    không gian còn lại của cửa sổ (đã co lại do windowSoftInputMode="adjustResize"),
     *    bàn phím càng thấp thì phần còn lại càng nhiều -> trackpad càng cao.
     */
    private fun setupAutoLayout() {
        applyLayoutState(keyboardVisible = false)

        val rootView = mainColumn.rootView
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val visibleFrame = Rect()
            rootView.getWindowVisibleDisplayFrame(visibleFrame)
            val screenHeight = rootView.height
            if (screenHeight <= 0) return@OnGlobalLayoutListener
            val keypadHeight = screenHeight - visibleFrame.bottom
            val visibleNow = keypadHeight > screenHeight * 0.15
            if (visibleNow != isKeyboardVisible) {
                applyLayoutState(visibleNow)
            }
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /** Chưa đăng ký HID -> ẩn dòng trạng thái, hiện nút để bấm đăng ký; đã đăng ký thì ngược lại. */
    private fun setHidRegisteredUi(registered: Boolean) {
        statusText.visibility = if (registered) android.view.View.VISIBLE else android.view.View.GONE
        btnRegisterHid.visibility = if (registered) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Đưa nút "Đăng ký..." về trạng thái bấm được lại, dùng khi luồng bật Bluetooth/xin quyền thất bại. */
    private fun resetRegisterButton() {
        btnRegisterHid.isEnabled = true
        btnRegisterHid.text = "Đăng ký làm bàn phím và chuột"
    }

    private fun applyLayoutState(keyboardVisible: Boolean) {
        isKeyboardVisible = keyboardVisible
        mainColumn.removeAllViews()

        val trackpadParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )

        mainColumn.addView(topBar)
        if (keyboardVisible) {
            // 3 phím lên trên -> rồi tới chuột, sát ngay phần bàn phím ảo bên dưới.
            mainColumn.addView(controlsRow)
            mainColumn.addView(divider)
            trackpad.layoutParams = trackpadParams
            mainColumn.addView(trackpad)
        } else {
            // Chưa bật bàn phím: hạ 3 phím xuống dưới cùng, chuột chiếm tối đa diện tích.
            trackpad.layoutParams = trackpadParams
            mainColumn.addView(trackpad)
            mainColumn.addView(divider)
            mainColumn.addView(controlsRow)
        }
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

    /**
     * Luôn kiểm tra/bật Bluetooth TRƯỚC, chỉ đăng ký HID SAU khi Bluetooth đã bật:
     *  - Bluetooth đã bật sẵn -> đăng ký HID ngay.
     *  - Bluetooth đang tắt -> hiện hộp thoại hệ thống yêu cầu bật; nếu người dùng
     *    đồng ý bật (enableBluetoothLauncher trả về RESULT_OK) thì mới đăng ký HID.
     */
    @SuppressLint("MissingPermission")
    private fun ensureBluetoothEnabledThenRegister() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            resetRegisterButton()
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
