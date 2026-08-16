package com.example.btremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * 3 chế độ layout (xem [FullscreenMode]):
 *  - NONE: trạng thái bình thường — trackpad + 3 hàng nút phía dưới.
 *  - MOUSE: chỉ còn trackpad, xoay ngang, chiếm toàn màn hình.
 *  - KEYBOARD: chỉ còn ô gõ đồng bộ, xoay ngang, chiếm toàn màn hình.
 * Bật/tắt MOUSE/KEYBOARD bằng cách nhấn đúp 1 trong 2 nút tròn nổi góc trên.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var hidManager: HidManager
    private lateinit var voiceInput: VoiceInputController
    private lateinit var syncInput: SyncInputController

    private lateinit var mainColumn: LinearLayout
    private lateinit var topBar: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var btnRegisterHid: MaterialButton
    private lateinit var trackpad: TrackpadView
    private lateinit var divider: View
    private lateinit var rowNav: LinearLayout
    private lateinit var rowVolume: LinearLayout
    private lateinit var rowMedia: LinearLayout
    private lateinit var syncInputBar: LinearLayout
    private lateinit var syncInputField: EditText
    private lateinit var btnMouseFullscreen: MaterialButton
    private lateinit var btnKeyboardFullscreen: MaterialButton
    private lateinit var btnVoiceInput: MaterialButton
    private lateinit var btnOpenKeyboard: MaterialButton

    private enum class FullscreenMode { NONE, MOUSE, KEYBOARD }
    private var fullscreenMode = FullscreenMode.NONE
    private var isKeyboardVisible = false
    private var lastManualKeyboardOpenAt = 0L
    private var voiceStartPos = 0

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
        else
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)

    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) ensureBluetoothEnabledThenRegister()
        else {
            resetRegisterButton()
            toast("Cần cấp quyền Bluetooth để hoạt động")
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) hidManager.start()
        else {
            resetRegisterButton()
            toast("Cần bật Bluetooth trước thì mới đăng ký làm bàn phím + chuột được")
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginVoiceListening() else toast("Cần cấp quyền Micro để nhập liệu bằng giọng nói")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        hidManager = HidManager(this).also { it.listener = buildHidListener() }
        voiceInput = VoiceInputController(this, ::onVoicePartialText, ::onVoiceStopped)
        syncInput = SyncInputController(syncInputField, hidManager)

        setupTrackpad()
        setupNavRow()
        setupVolumeRow()
        setupMediaRow()
        setupSyncInputBar()
        setupFullscreenToggles()
        setupKeyboardAutoLayout()

        setHidRegisteredUi(registered = false)
        btnRegisterHid.setOnClickListener {
            btnRegisterHid.isEnabled = false
            btnRegisterHid.text = "Đang đăng ký..."
            autoRegisterAndPromptBluetooth()
        }

        autoRegisterAndPromptBluetooth()
    }

    override fun onDestroy() {
        voiceInput.destroy()
        hidManager.unregister()
        super.onDestroy()
    }

    private fun bindViews() {
        mainColumn = findViewById(R.id.mainColumn)
        topBar = findViewById(R.id.topBar)
        statusText = findViewById(R.id.statusText)
        btnRegisterHid = findViewById(R.id.btnRegisterHid)
        trackpad = findViewById(R.id.trackpad)
        divider = findViewById(R.id.divider)
        rowNav = findViewById(R.id.rowNav)
        rowVolume = findViewById(R.id.rowVolume)
        rowMedia = findViewById(R.id.rowMedia)
        syncInputBar = findViewById(R.id.syncInputBar)
        syncInputField = findViewById(R.id.syncInput)
        btnMouseFullscreen = findViewById(R.id.btnMouseFullscreen)
        btnKeyboardFullscreen = findViewById(R.id.btnKeyboardFullscreen)
        btnVoiceInput = findViewById(R.id.btnVoiceInput)
        btnOpenKeyboard = findViewById(R.id.btnOpenKeyboard)
    }

    // ---------- Đăng ký HID + kết nối thiết bị ----------

    private fun buildHidListener() = object : HidManager.Listener {
        override fun onRegistered() = runOnUiThread {
            setHidRegisteredUi(registered = true)
            statusText.text = "Chưa kết nối thiết bị nào — hãy chọn thiết bị để kết nối"
            hidManager.autoReconnectLastDevice()
        }

        override fun onUnregistered() = runOnUiThread { setHidRegisteredUi(registered = false) }

        override fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean) = runOnUiThread {
            setHidRegisteredUi(registered = true)
            statusText.text = if (connected) "Đang kết nối tới: ${safeName(device)}" else "Chưa kết nối thiết bị nào"
        }

        override fun onError(message: String) = runOnUiThread {
            resetRegisterButton()
            toast(message)
        }
    }

    private fun autoRegisterAndPromptBluetooth() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) ensureBluetoothEnabledThenRegister()
        else startupPermissionLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun ensureBluetoothEnabledThenRegister() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            resetRegisterButton()
            toast("Thiết bị này không có Bluetooth")
            return
        }
        if (adapter.isEnabled) hidManager.start()
        else enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun setHidRegisteredUi(registered: Boolean) {
        statusText.visibility = if (registered) View.VISIBLE else View.GONE
        btnRegisterHid.visibility = if (registered) View.GONE else View.VISIBLE
    }

    private fun resetRegisterButton() {
        btnRegisterHid.isEnabled = true
        btnRegisterHid.text = "Đăng ký làm bàn phím và chuột"
    }

    @SuppressLint("MissingPermission")
    private fun showBondedDevicesDialog() {
        val devices = hidManager.bondedDevices().toList()
        if (devices.isEmpty()) {
            toast("Chưa pair với thiết bị nào. Vào Cài đặt > Bluetooth, pair với TV/PC trước.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Chọn thiết bị để kết nối")
            .setItems(devices.map { safeName(it) }.toTypedArray()) { _, which -> hidManager.connectTo(devices[which]) }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice?): String {
        if (device == null) return "?"
        return try { device.name ?: device.address } catch (e: SecurityException) { device.address }
    }

    // ---------- Trackpad + 3 hàng nút ----------

    private fun setupTrackpad() {
        trackpad.onMove = { dx, dy -> hidManager.sendMouseMove(dx, dy); syncInput.reset() }
        trackpad.onClick = { rightButton -> hidManager.sendMouseClick(rightButton); syncInput.reset() }
        trackpad.onScroll = { dy -> hidManager.sendMouseScroll(dy); syncInput.reset() }
    }

    private fun setupNavRow() {
        findViewById<MaterialButton>(R.id.btnPickDevice).setOnClickListener { showBondedDevicesDialog() }
        findViewById<MaterialButton>(R.id.btnHome).setOnClickListener {
            hidManager.sendSpecialKey("HOME")
            syncInput.reset()
        }
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { hidManager.sendSpecialKey("ESC") }
        btnOpenKeyboard.setOnClickListener { toggleVirtualKeyboard() }
    }

    private fun setupVolumeRow() {
        findViewById<MaterialButton>(R.id.btnScreenOff).setOnClickListener {
            hidManager.sendScreenOff()
            toast("Đã gửi lệnh tắt màn hình (chỉ hoạt động với TV)")
        }
        findViewById<MaterialButton>(R.id.btnVolumeDown).setOnClickListener { hidManager.sendVolumeDown() }
        findViewById<MaterialButton>(R.id.btnVolumeUp).setOnClickListener { hidManager.sendVolumeUp() }
        btnVoiceInput.setOnClickListener { toggleVoiceInput() }
    }

    private fun setupMediaRow() {
        findViewById<MaterialButton>(R.id.btnRewind).setOnClickListener { hidManager.sendRewind() }
        findViewById<MaterialButton>(R.id.btnPreviousTrack).setOnClickListener { hidManager.sendPreviousTrack() }
        findViewById<MaterialButton>(R.id.btnPlayPause).setOnClickListener { hidManager.sendPlayPause() }
        findViewById<MaterialButton>(R.id.btnNextTrack).setOnClickListener { hidManager.sendNextTrack() }
        findViewById<MaterialButton>(R.id.btnFastForward).setOnClickListener { hidManager.sendFastForward() }
    }

    // ---------- Bàn phím ảo + thanh gõ đồng bộ ----------

    private fun setupSyncInputBar() {
        findViewById<MaterialButton>(R.id.btnPasteClipboard).setOnClickListener { pasteClipboard() }
        findViewById<MaterialButton>(R.id.btnClearSyncInput).setOnClickListener { syncInput.reset() }
    }

    private fun toggleVirtualKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (isKeyboardVisible) {
            imm.hideSoftInputFromWindow(syncInputField.windowToken, 0)
            return
        }
        lastManualKeyboardOpenAt = System.currentTimeMillis()
        if (syncInputBar.parent == null) applyLayoutState(keyboardVisible = true)
        syncInputField.requestFocus()
        // SHOW_FORCED thay vì SHOW_IMPLICIT: tránh bị hệ thống âm thầm bỏ qua nếu
        // người dùng từng tự đóng bàn phím trước đó.
        imm.showSoftInput(syncInputField, InputMethodManager.SHOW_FORCED)
    }

    /** Theo dõi chiều cao bàn phím ảo hệ thống để tự dựng lại layout khi nó hiện/ẩn. */
    private fun setupKeyboardAutoLayout() {
        applyLayoutState(keyboardVisible = false)
        val rootView = mainColumn.rootView
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleFrame = Rect()
            rootView.getWindowVisibleDisplayFrame(visibleFrame)
            val screenHeight = rootView.height
            if (screenHeight <= 0) return@addOnGlobalLayoutListener
            val keypadHeight = screenHeight - visibleFrame.bottom
            val visibleNow = keypadHeight > screenHeight * 0.15
            // Bỏ qua lần đọc sai ngay sau khi TỰ mở bàn phím (bàn phím ảo thật chưa
            // kịp trượt lên trong vài khung hình đầu).
            val justOpenedManually = isKeyboardVisible &&
                System.currentTimeMillis() - lastManualKeyboardOpenAt < 600
            if (visibleNow != isKeyboardVisible && !(!visibleNow && justOpenedManually)) {
                applyLayoutState(visibleNow)
            }
        }
    }

    private fun pasteClipboard() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            toast("Clipboard trống, chưa copy văn bản nào")
            return
        }
        if (syncInputBar.parent == null) applyLayoutState(keyboardVisible = true)
        syncInputField.requestFocus()
        syncInput.insertText(text)
    }

    // ---------- Nhập liệu bằng giọng nói ----------

    private fun toggleVoiceInput() {
        if (voiceInput.isListening) {
            voiceInput.stop(sentEnter = true)
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginVoiceListening()
    }

    private fun beginVoiceListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Máy này không hỗ trợ nhận diện giọng nói")
            return
        }
        if (syncInputBar.parent == null) applyLayoutState(keyboardVisible = true)
        syncInputField.requestFocus()
        voiceStartPos = syncInput.cursorPosition()
        voiceInput.start()
        updateVoiceButtonVisualState(listening = true)
    }

    // Kết quả tạm là toàn bộ câu tính từ lúc bắt đầu nói -> luôn thay từ voiceStartPos tới hết.
    private fun onVoicePartialText(text: String) = syncInput.replaceFrom(voiceStartPos, text)

    private fun onVoiceStopped(sentEnter: Boolean) {
        updateVoiceButtonVisualState(listening = false)
        if (sentEnter) hidManager.sendSpecialKey("ENTER")
    }

    private fun updateVoiceButtonVisualState(listening: Boolean) {
        btnVoiceInput.backgroundTintList = ColorStateList.valueOf(
            if (listening) 0xFFE53935.toInt() else ContextCompat.getColor(this, R.color.key_default)
        )
    }

    // ---------- 2 nút tròn nổi: chuột / bàn phím toàn màn hình ----------

    private fun setupFullscreenToggles() {
        setupDoubleTapToggle(btnMouseFullscreen, FullscreenMode.MOUSE)
        setupDoubleTapToggle(btnKeyboardFullscreen, FullscreenMode.KEYBOARD)
    }

    // Chỉ nhận NHẤN ĐÚP: 2 nút này nằm sát vùng trackpad, nhấn 1 chạm dễ trúng nhầm lúc lướt ngón tay.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDoubleTapToggle(button: MaterialButton, mode: FullscreenMode) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFullscreenMode(mode)
                return true
            }
        })
        button.setOnTouchListener { _, event -> detector.onTouchEvent(event); true }
    }

    private fun toggleFullscreenMode(mode: FullscreenMode) {
        val turningOn = fullscreenMode != mode
        fullscreenMode = if (fullscreenMode == mode) FullscreenMode.NONE else mode
        applyFullscreenMode()

        val label = if (mode == FullscreenMode.MOUSE) "chuột" else "bàn phím"
        toast(
            if (turningOn) "Đã bật chế độ $label toàn màn hình — nhấn đúp lại để tắt"
            else "Đã tắt chế độ $label toàn màn hình"
        )
    }

    private fun applyFullscreenMode() {
        updateToggleButtonVisualState(btnMouseFullscreen, fullscreenMode == FullscreenMode.MOUSE)
        updateToggleButtonVisualState(btnKeyboardFullscreen, fullscreenMode == FullscreenMode.KEYBOARD)

        requestedOrientation = if (fullscreenMode == FullscreenMode.NONE)
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        if (fullscreenMode == FullscreenMode.KEYBOARD) lastManualKeyboardOpenAt = System.currentTimeMillis()
        applyLayoutState(isKeyboardVisible)

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (fullscreenMode == FullscreenMode.KEYBOARD) {
            syncInputField.requestFocus()
            imm.showSoftInput(syncInputField, InputMethodManager.SHOW_FORCED)
        } else {
            imm.hideSoftInputFromWindow(syncInputField.windowToken, 0)
        }
    }

    private fun updateToggleButtonVisualState(button: MaterialButton, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (active) 0xFF4FC3F7.toInt() else 0x40000000)
    }

    // ---------- Dựng lại layout theo chế độ hiện tại ----------

    private fun applyLayoutState(keyboardVisible: Boolean) {
        isKeyboardVisible = keyboardVisible
        mainColumn.removeAllViews()

        val fillRemaining = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        when (fullscreenMode) {
            FullscreenMode.MOUSE -> {
                trackpad.layoutParams = fillRemaining
                mainColumn.addView(trackpad)
            }
            FullscreenMode.KEYBOARD -> {
                syncInputBar.visibility = View.VISIBLE
                syncInputField.layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            FullscreenMode.NONE -> {
                mainColumn.addView(topBar)
                trackpad.layoutParams = fillRemaining
                mainColumn.addView(trackpad)
                mainColumn.addView(divider)
                mainColumn.addView(rowNav)
                mainColumn.addView(rowVolume)
                mainColumn.addView(rowMedia)
                syncInputBar.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                syncInputField.layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                syncInputBar.visibility = if (keyboardVisible) View.VISIBLE else View.GONE
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
