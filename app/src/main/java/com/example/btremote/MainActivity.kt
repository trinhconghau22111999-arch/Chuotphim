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
import androidx.activity.OnBackPressedCallback
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

    private lateinit var rootContainer: FrameLayout
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
    private lateinit var overlayUnregistered: FrameLayout
    private lateinit var btnRegisterHidOverlay: MaterialButton

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
        CrashHandler.install(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        showLastCrashIfAny()

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
        setupBackPressToExitFullscreen()

        setHidRegisteredUi(registered = false)

        // Nút đăng ký trên topBar (ẩn vì đã dùng overlay thay thế)
        btnRegisterHid.visibility = View.GONE

        // Nút đăng ký trên overlay giữa màn hình
        btnRegisterHidOverlay.setOnClickListener {
            btnRegisterHidOverlay.isEnabled = false
            btnRegisterHidOverlay.text = "Đang đăng ký..."
            autoRegisterAndPromptBluetooth()
        }

        // Nếu đã từng đăng ký thành công trước đó thì tự đăng ký lại
        if (wasRegisteredBefore()) {
            overlayUnregistered.visibility = View.GONE
            autoRegisterAndPromptBluetooth()
        } else {
            overlayUnregistered.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        voiceInput.destroy()
        hidManager.unregister()
        super.onDestroy()
    }

    /** Hiện ngay lỗi của lần văng app gần nhất (nếu có) — chỉ hiện 1 lần rồi thôi. */
    private fun showLastCrashIfAny() {
        val trace = CrashHandler.consumeLastCrash(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("App vừa văng ở lần mở trước")
            .setMessage(trace)
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Copy") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("crash_log", trace))
                toast("Đã copy log lỗi vào clipboard")
            }
            .setCancelable(true)
            .show()
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
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
        overlayUnregistered = findViewById(R.id.overlayUnregistered)
        btnRegisterHidOverlay = findViewById(R.id.btnRegisterHidOverlay)
    }

    // ---------- Đăng ký HID + kết nối thiết bị ----------

    private fun buildHidListener() = object : HidManager.Listener {
        override fun onRegistered() = runOnUiThread {
            setHidRegisteredUi(registered = true)
            saveRegisteredState(true)
            overlayUnregistered.visibility = View.GONE
            statusText.text = "Chưa kết nối thiết bị nào — Hãy nhấn phím bên dưới để chọn thiết bị kết nối."
            hidManager.autoReconnectLastDevice()
        }

        override fun onUnregistered() = runOnUiThread {
            // Không làm gì nếu đã đăng ký thành công trước đó — callback này fire
            // cả khi app đang thoát bình thường (onDestroy → unregister), không
            // nên hiện overlay hay reset UI vì nó sẽ flash xấu và gây nhầm lẫn.
            if (!wasRegisteredBefore()) {
                setHidRegisteredUi(registered = false)
                overlayUnregistered.visibility = View.VISIBLE
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean) = runOnUiThread {
            setHidRegisteredUi(registered = true)
            // Khi kết nối được thiết bị = chắc chắn đã registered thành công
            saveRegisteredState(true)
            overlayUnregistered.visibility = View.GONE
            statusText.text = if (connected) "Đã kết nối tới: ${safeName(device)}"
                else "Chưa kết nối thiết bị nào — Hãy nhấn phím bên dưới để chọn thiết bị kết nối."
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
        // btnRegisterHid ẩn vĩnh viễn — dùng overlayUnregistered thay thế
        btnRegisterHid.visibility = View.GONE
        // Khi đã đăng ký: ẩn overlay. Khi mất đăng ký: chỉ hiện overlay nếu chưa
        // từng đăng ký thành công (tránh hiện lại overlay khi app đang thoát)
        if (registered) {
            overlayUnregistered.visibility = View.GONE
        }
    }

    private fun resetRegisterButton() {
        btnRegisterHid.isEnabled = true
        btnRegisterHid.text = "Đăng ký làm bàn phím và chuột"
        btnRegisterHidOverlay.isEnabled = true
        btnRegisterHidOverlay.text = "Đăng ký làm bàn phím và chuột"
        // Hiện lại overlay nếu chưa đăng ký thành công lần nào
        if (!wasRegisteredBefore()) {
            overlayUnregistered.visibility = View.VISIBLE
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBondedDevicesDialog() {
        // Trước khi cho chọn thiết bị: nếu điện thoại này TỪNG ghép nối Bluetooth
        // với thiết bị nhận (TV/đầu thu) theo cách thông thường (ngoài app, ví dụ
        // qua Cài đặt hệ thống hoặc app điều khiển khác) từ trước, liên kết cũ đó
        // có thể xung đột với kiểu ghép nối HID mà app này dùng. Cảnh báo 1 lần
        // duy nhất, nhắc xoá (hủy ghép nối) thiết bị đó trong Cài đặt Bluetooth
        // của điện thoại rồi mới quay lại ghép nối từ trong app.
        if (!hasShownUnpairNotice()) {
            markUnpairNoticeShown()
            showUnpairNoticeDialog { showBondedDevicesDialogInternal() }
            return
        }
        showBondedDevicesDialogInternal()
    }

    /** Hộp thoại nhắc xoá ghép nối Bluetooth cũ trước khi kết nối lại từ trong app —
     *  dùng cả cho lần hiện tự động đầu tiên lẫn khi người dùng chủ động xem lại
     *  (giữ nhấn nút "Chọn thiết bị"). */
    private fun showUnpairNoticeDialog(onContinue: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Lưu ý trước khi kết nối")
            .setMessage(
                "Nếu điện thoại này đã từng ghép nối Bluetooth với TV/đầu thu " +
                    "TRƯỚC ĐÂY (ví dụ ghép nối ngoài Cài đặt hệ thống hoặc bằng " +
                    "app điều khiển khác), hãy vào Cài đặt Bluetooth của điện " +
                    "thoại, XOÁ (hủy ghép nối) thiết bị đó trước, rồi quay lại " +
                    "đây chọn thiết bị để kết nối lại từ trong app.\n\n" +
                    "Bỏ qua bước này có thể khiến app không kết nối được hoặc " +
                    "kết nối chập chờn."
            )
            .setPositiveButton("Đã hiểu, tiếp tục") { _, _ -> onContinue() }
            .setCancelable(false)
            .show()
    }

    private fun hasShownUnpairNotice(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_UNPAIR_NOTICE_SHOWN, false)

    private fun markUnpairNoticeShown() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_UNPAIR_NOTICE_SHOWN, true)
            .apply()
    }

    @SuppressLint("MissingPermission")
    private fun showBondedDevicesDialogInternal() {
        // "Quét thiết bị mới…" giờ mở THẲNG trang Cài đặt Bluetooth của hệ thống
        // (thay vì dialog tự quét/tự ghép nối trong app trước đây) — người dùng
        // ghép nối (pair) trực tiếp ở đó, xong quay lại app bấm "Chọn thiết bị"
        // để chọn máy vừa ghép nối trong danh sách đã pair.
        val bonded = hidManager.bondedDevices().toList()
        val scanLabel = "🔍  Quét thiết bị mới…"
        val items = (bonded.map { safeName(it) } + scanLabel).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chọn thiết bị để kết nối")
            .setItems(items) { _, which ->
                if (which < bonded.size) hidManager.connectTo(bonded[which]) else openSystemBluetoothSettings()
            }
            .show()
    }

    private fun openSystemBluetoothSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) {
            toast("Máy này không có trang Cài đặt Bluetooth để mở")
        }
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
        findViewById<MaterialButton>(R.id.btnPickDevice).apply {
            setOnClickListener { showBondedDevicesDialog() }
            // Giữ nhấn để xem lại lưu ý "xoá ghép nối cũ" bất cứ lúc nào, kể cả sau
            // khi đã hiện 1 lần rồi (phòng khi người dùng bỏ lỡ hoặc quên).
            setOnLongClickListener { showUnpairNoticeDialog { showBondedDevicesDialogInternal() }; true }
        }
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
        findViewById<MaterialButton>(R.id.btnClearSyncInput).setOnClickListener { syncInput.clearAll() }
    }

    private fun toggleVirtualKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (isKeyboardVisible) {
            imm.hideSoftInputFromWindow(syncInputField.windowToken, 0)
            return
        }
        lastManualKeyboardOpenAt = System.currentTimeMillis()
        // BUG CŨ: kiểm tra "syncInputBar.parent == null" để quyết định có cần dựng
        // lại layout hay không — nhưng syncInputBar nằm trực tiếp trong FrameLayout
        // gốc (xem activity_main.xml), KHÔNG phải con của mainColumn, nên parent
        // của nó không bao giờ null. Điều kiện luôn sai -> applyLayoutState() không
        // bao giờ chạy -> ô nhập vẫn ở trạng thái GONE -> requestFocus() thất bại
        // âm thầm -> bàn phím không mở, màn hình như đứng hình (giống app bị văng).
        // Sửa: luôn dựng lại layout ở đây, không cần điều kiện.
        applyLayoutState(keyboardVisible = true)
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
        // syncInputBar không phải con của mainColumn nên parent không bao giờ null
        // (xem giải thích chi tiết ở toggleVirtualKeyboard) -> luôn dựng lại layout,
        // không dùng điều kiện chết đó.
        applyLayoutState(keyboardVisible = true)
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
        // Cùng lý do như pasteClipboard()/toggleVirtualKeyboard(): điều kiện parent==null
        // luôn sai nên luôn gọi thẳng, không kiểm tra.
        applyLayoutState(keyboardVisible = true)
        syncInputField.requestFocus()
        // Chèn khoảng trắng trước khi nghe câu mới, tránh dính liền vào chữ đã có
        // (xem ensureTrailingSpace() trong SyncInputController).
        syncInput.ensureTrailingSpace()
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
            if (listening) ContextCompat.getColor(this, R.color.key_neon_green)
            else ContextCompat.getColor(this, R.color.key_default)
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

    // Bấm phím Back của hệ thống khi đang ở chế độ toàn màn hình (chuột/bàn phím,
    // xoay ngang) thì thoát chế độ đó, xoay dọc về màn hình bình thường — thay vì
    // thoát hẳn app như hành vi Back mặc định.
    private fun setupBackPressToExitFullscreen() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenMode != FullscreenMode.NONE) {
                    fullscreenMode = FullscreenMode.NONE
                    applyFullscreenMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun updateToggleButtonVisualState(button: MaterialButton, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(
            if (active) ContextCompat.getColor(this, R.color.key_active) else 0x40000000
        )
    }

    // ---------- Dựng lại layout theo chế độ hiện tại ----------

    private fun applyLayoutState(keyboardVisible: Boolean) {
        isKeyboardVisible = keyboardVisible
        mainColumn.removeAllViews()

        // LUÔN gỡ syncInputBar khỏi cha hiện tại trước (dù đang ở mainColumn hay
        // rootContainer) rồi mới gắn lại đúng chỗ bên dưới theo từng chế độ. Nếu
        // không gỡ tay, addView() ở 1 view đã có cha sẵn sẽ ném
        // IllegalStateException ("The specified child already has a parent").
        (syncInputBar.parent as? android.view.ViewGroup)?.removeView(syncInputBar)

        val fillRemaining = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        when (fullscreenMode) {
            FullscreenMode.MOUSE -> {
                trackpad.layoutParams = fillRemaining
                mainColumn.addView(trackpad)
                // syncInputBar đã bị gỡ khỏi cha ở trên -> không gắn lại đâu cả,
                // coi như ẩn hẳn trong lúc chuột toàn màn hình.
                syncInputBar.visibility = View.GONE
            }
            FullscreenMode.KEYBOARD -> {
                // Chế độ bàn phím toàn màn hình: syncInputBar là view DUY NHẤT hiện ra,
                // không nằm trong mainColumn (đang rỗng) -> vẫn cần gắn thẳng vào
                // rootContainer với FrameLayout.LayoutParams, ghim đáy màn hình.
                rootContainer.addView(
                    syncInputBar,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = android.view.Gravity.BOTTOM }
                )
                syncInputBar.visibility = View.VISIBLE
                syncInputField.layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            FullscreenMode.NONE -> {
                trackpad.layoutParams = fillRemaining
                mainColumn.addView(trackpad)
                mainColumn.addView(divider)
                mainColumn.addView(topBar)
                mainColumn.addView(rowNav)
                mainColumn.addView(rowVolume)
                mainColumn.addView(rowMedia)
                if (keyboardVisible) {
                    // QUAN TRỌNG: trước đây syncInputBar được ghim vào rootContainer
                    // (FrameLayout.LayoutParams, gravity=bottom) TÁCH RỜI khỏi
                    // mainColumn. Vì windowSoftInputMode="adjustResize" thu nhỏ CẢ
                    // rootContainer LẪN mainColumn xuống đúng vùng hiển thị còn lại
                    // phía trên bàn phím, cả 2 cùng co lại NGANG NHAU -> đáy
                    // mainColumn (rowMedia) và đáy rootContainer (syncInputBar,
                    // gravity=bottom) trùng đúng 1 vị trí -> syncInputBar (khai báo
                    // sau mainColumn trong XML nên vẽ đè lên trên) CHE MẤT rowMedia/
                    // rowVolume thay vì nằm gọn phía dưới chúng.
                    // Sửa: gắn syncInputBar làm con TRỰC TIẾP của mainColumn, ngay
                    // sau rowMedia, dùng LinearLayout.LayoutParams bình thường. Nhờ
                    // vậy nó xếp NGAY SAU rowMedia trong dòng chảy dọc, 3 hàng phím
                    // (rowNav/rowVolume/rowMedia) luôn nằm phía trên nó và phía trên
                    // bàn phím ảo, không còn bị đè/che nữa.
                    mainColumn.addView(
                        syncInputBar,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                    syncInputField.layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    syncInputBar.visibility = View.VISIBLE
                } else {
                    syncInputBar.visibility = View.GONE
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** Kiểm tra xem người dùng đã từng đăng ký HID thành công chưa (lưu qua SharedPreferences). */
    private fun wasRegisteredBefore(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_HID_REGISTERED, false)

    /** Lưu trạng thái đăng ký HID vào SharedPreferences. */
    private fun saveRegisteredState(registered: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_HID_REGISTERED, registered)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "btremote_prefs"
        private const val KEY_UNPAIR_NOTICE_SHOWN = "unpair_notice_shown"
        private const val KEY_HID_REGISTERED = "hid_registered"
    }
}
