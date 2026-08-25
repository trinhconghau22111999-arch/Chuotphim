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
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * 3 chế độ layout (xem [FullscreenMode]):
 *  - NONE: trạng thái bình thường — trackpad + 3 hàng nút phía dưới.
 *  - MOUSE: chỉ còn trackpad, xoay ngang, chiếm toàn màn hình.
 *  - KEYBOARD: chỉ còn ô gõ đồng bộ, xoay ngang, chiếm toàn màn hình.
 * Bật/tắt MOUSE/KEYBOARD bằng cách nhấn đúp 1 trong 2 nút tròn nổi góc trên.
 *
 * Ô gõ đồng bộ ("Đang gõ trên TV") là cửa sổ nổi [FloatingSyncBar] hoàn toàn
 * độc lập với layout chính — không cần addView/removeView phức tạp, EditText
 * luôn attached nên focus/IME hoạt động đúng. Vị trí tự điều chỉnh:
 *   - Bàn phím ảo đang mở  → ngay trên bàn phím
 *   - Mic / không bàn phím → ngay trên 3 hàng nút
 */
class MainActivity : AppCompatActivity() {

    private lateinit var hidManager: HidManager
    private lateinit var voiceInput: VoiceInputController
    private lateinit var syncInput: SyncInputController
    private var proximityConnector: ProximityAutoConnector? = null

    // Cửa sổ nổi ô gõ đồng bộ — thay thế hoàn toàn syncInputBar cũ trong XML
    private lateinit var floatBar: FloatingSyncBar

    private lateinit var rootContainer: FrameLayout
    private lateinit var mainColumn: LinearLayout
    private lateinit var topBar: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var btnRegisterHid: MaterialButton
    private lateinit var trackpad: TrackpadView
    private lateinit var divider: View
    private lateinit var rowNav: LinearLayout
    private lateinit var rowVolume: LinearLayout
    private lateinit var rowDpad: LinearLayout
    private lateinit var rowMedia: LinearLayout
    private lateinit var btnMouseFullscreen: MaterialButton
    private lateinit var btnKeyboardFullscreen: MaterialButton
    private lateinit var btnVoiceInput: MaterialButton
    private lateinit var btnOpenKeyboard: MaterialButton
    private lateinit var overlayUnregistered: FrameLayout
    private lateinit var btnRegisterHidOverlay: MaterialButton

    private enum class FullscreenMode { NONE, MOUSE, KEYBOARD }
    private var fullscreenMode = FullscreenMode.NONE

    // true khi floatBar đang nên hiện (bàn phím hoặc mic đang hoạt động)
    private var isInputBarVisible = false
    private var voiceStartPos = 0

    // Trạng thái bàn phím ảo hệ thống thật sự
    private var isImeActuallyVisible = false
    private var imeHeightPx = 0        // chiều cao bàn phím tính từ đáy màn hình
    private var rowsHeightPx = 0       // chiều cao 3 hàng nút tính từ đáy màn hình (cache)
    // Từ khi bật edge-to-edge (setDecorFitsSystemWindows=false), rootContainer được padding
    // đúng bằng inset thanh điều hướng hệ thống — bottomMargin của floatBar lại tính trong
    // phạm vi ĐÃ trừ padding đó, nên phải trừ giá trị này khỏi imeHeightPx thì mới ra đúng
    // khoảng cách "ngay trên bàn phím" (nếu không ô gõ sẽ bị đẩy lên cao hơn cần thiết).
    private var systemBarsBottomPx = 0
    // Mép trên của floatBar (px, tính từ đáy rootContainer) lần cập nhật gần nhất từ
    // FloatingSyncBar.onLayoutChanged — dùng để tính lại bottomInsetPx của trackpad mỗi
    // khi rowsHeightPx đổi (vd xoay màn hình / đổi chế độ) mà không cần đợi floatBar tự bắn lại.
    private var floatBarTopFromBottomPx = 0

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
        else
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )

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

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* TV sẽ tự kết nối vào phone sau khi tìm thấy và pair */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.install(this)
        super.onCreate(savedInstanceState)
        // Bắt buộc phải tắt "decor fits system windows" thì ViewCompat.setOnApplyWindowInsetsListener
        // mới nhận được đúng WindowInsetsCompat.Type.ime() (chiều cao bàn phím ảo thật) khi
        // windowSoftInputMode="adjustNothing" — thiếu dòng này là nguyên nhân khiến imeHeightPx
        // luôn = 0 hoặc không cập nhật, làm ô gõ đồng bộ (floatBar) không bao giờ trồi lên đúng
        // ngay trên bàn phím mà đứng lì ở vị trí cache cũ (vd tuột lên đầu màn hình).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        showLastCrashIfAny()
        bindViews()


        hidManager = HidManager(this).also { it.listener = buildHidListener() }
        voiceInput = VoiceInputController(this, ::onVoicePartialText, ::onVoiceStopped)

        // Khởi tạo cửa sổ nổi ô gõ đồng bộ — add vào rootContainer một lần duy nhất
        floatBar = FloatingSyncBar(
            activity      = this,
            rootContainer = rootContainer,
            onPaste       = { pasteClipboard() },
            onClear       = { syncInput.clearAll() }
        )
        syncInput = SyncInputController(floatBar.editText, hidManager)
        floatBar.onLayoutChanged = { topFromBottomPx -> updateTrackpadBottomInset(topFromBottomPx) }

        setupTrackpad()
        setupNavRow()
        setupVolumeRow()
        setupDpadRow()
        setupMediaRow()
        setupFullscreenToggles()
        setupTrackpadTopInset()
        setupKeyboardListener()
        setupBackPressToExitFullscreen()
        applyLayoutState()

        setHidRegisteredUi(registered = false)
        btnRegisterHid.visibility = View.GONE

        btnRegisterHidOverlay.setOnClickListener {
            btnRegisterHidOverlay.isEnabled = false
            btnRegisterHidOverlay.text = "Đang đăng ký..."
            autoRegisterAndPromptBluetooth()
        }

        if (wasRegisteredBefore()) {
            overlayUnregistered.visibility = View.GONE
            autoRegisterAndPromptBluetooth()
        } else {
            overlayUnregistered.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        proximityConnector?.stop()
        voiceInput.destroy()
        hidManager.unregister()
        floatBar.hide()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (wasRegisteredBefore()) recheckHidConnectionOnResume()
    }

    @SuppressLint("MissingPermission")
    private fun recheckHidConnectionOnResume() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return
        if (!hidManager.isRegistered) {
            hidManager.start()
        } else if (!hidManager.isConnected) {
            hidManager.autoReconnectLastDevice()
        }
    }

    private fun startProximityConnectorIfNeeded() {
        if (hidManager.bondedDevices().size <= 1) return
        if (proximityConnector != null) return
        proximityConnector = ProximityAutoConnector(
            context = this,
            hidManager = hidManager,
            onFallbackConnected = { newDevice, oldDevice ->
                val oldName = try { oldDevice.name ?: "không rõ" } catch (_: Exception) { "không rõ" }
                val newName = try { newDevice.name ?: newDevice.address } catch (_: Exception) { newDevice.address }
                toast("$oldName ngoài tầm, không nối lại được — tự chuyển sang: $newName")
            },
            onRestoredOriginal = { device ->
                val name = try { device.name ?: device.address } catch (_: Exception) { device.address }
                toast("Đã nối lại được thiết bị gốc: $name")
            }
        )
    }

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
        rootContainer        = findViewById(R.id.rootContainer)
        mainColumn           = findViewById(R.id.mainColumn)
        topBar               = findViewById(R.id.topBar)
        statusText           = findViewById(R.id.statusText)
        btnRegisterHid       = findViewById(R.id.btnRegisterHid)
        trackpad             = findViewById(R.id.trackpad)
        divider              = findViewById(R.id.divider)
        rowNav               = findViewById(R.id.rowNav)
        rowVolume            = findViewById(R.id.rowVolume)
        rowDpad              = findViewById(R.id.rowDpad)
        rowMedia             = findViewById(R.id.rowMedia)
        btnMouseFullscreen   = findViewById(R.id.btnMouseFullscreen)
        btnKeyboardFullscreen= findViewById(R.id.btnKeyboardFullscreen)
        btnVoiceInput        = findViewById(R.id.btnVoiceInput)
        btnOpenKeyboard      = findViewById(R.id.btnOpenKeyboard)
        overlayUnregistered  = findViewById(R.id.overlayUnregistered)
        btnRegisterHidOverlay= findViewById(R.id.btnRegisterHidOverlay)
        // Không còn bind syncInputBar / syncInput từ XML nữa
    }

    // ---------- Đăng ký HID + kết nối thiết bị ----------

    private fun buildHidListener() = object : HidManager.Listener {
        override fun onRegistered() = runOnUiThread {
            setHidRegisteredUi(registered = true)
            saveRegisteredState(true)
            overlayUnregistered.visibility = View.GONE
            statusText.text = "Chưa kết nối thiết bị nào — Hãy nhấn phím ⚙️ bên dưới để chọn thiết bị nối kết."
            // Thử kết nối lại thiết bị đã pair trước (nếu có) — TV tự connect vào phone.
            val reconnected = hidManager.autoReconnectLastDevice()
            if (!reconnected) {
                // Chưa từng pair với TV nào → bật discoverable để TV tìm thấy phone.
                // Hộp thoại chỉ hiện 1 lần này — sau khi pair xong TV tự kết nối lại.
                requestDiscoverable()
            }
            startProximityConnectorIfNeeded()
        }

        override fun onUnregistered() = runOnUiThread {
            if (!wasRegisteredBefore()) {
                setHidRegisteredUi(registered = false)
                overlayUnregistered.visibility = View.VISIBLE
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean, wasIntentional: Boolean) = runOnUiThread {
            setHidRegisteredUi(registered = true)
            saveRegisteredState(true)
            overlayUnregistered.visibility = View.GONE
            statusText.text = if (connected) "Đã kết nối tới: ${safeName(device)}"
                else "Chưa kết nối thiết bị nào — Hãy nhấn phím ⚙️ bên dưới để chọn thiết bị nối kết."
            if (!connected && device != null && !wasIntentional) {
                proximityConnector?.onDisconnected(device)
            }
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
        if (missing.isEmpty()) {
            ensureBluetoothEnabledThenRegister()
        } else if (Manifest.permission.ACCESS_FINE_LOCATION in missing) {
            AlertDialog.Builder(this)
                .setTitle("Cần quyền Vị trí để quét Bluetooth")
                .setMessage(
                    "Trên phiên bản Android này, hệ thống bắt buộc phải có quyền Vị trí " +
                    "thì mới cho phép quét thiết bị Bluetooth xung quanh.\n\n" +
                    "Ứng dụng KHÔNG dùng để định vị GPS hay theo dõi vị trí của bạn — " +
                    "đây chỉ là yêu cầu kỹ thuật của Android để dò tìm và ghép nối TV/PC."
                )
                .setCancelable(false)
                .setPositiveButton("Tiếp tục") { _, _ ->
                    startupPermissionLauncher.launch(missing.toTypedArray())
                }
                .setNegativeButton("Để sau") { _, _ -> resetRegisterButton() }
                .show()
        } else {
            startupPermissionLauncher.launch(missing.toTypedArray())
        }
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
        btnRegisterHid.visibility = View.GONE
        if (registered) overlayUnregistered.visibility = View.GONE
    }

    private fun resetRegisterButton() {
        btnRegisterHid.isEnabled = true
        btnRegisterHid.text = "Đăng ký làm bàn phím và chuột"
        btnRegisterHidOverlay.isEnabled = true
        btnRegisterHidOverlay.text = "Đăng ký làm bàn phím và chuột"
        if (!wasRegisteredBefore()) overlayUnregistered.visibility = View.VISIBLE
    }

    /** Bật discoverable 300 giây để TV/PC tìm thấy phone lần đầu pair.
     *  Vào BT Settings TV → Quét → thấy tên điện thoại → bấm Kết nối. */
    private fun requestDiscoverable() {
        statusText.text = "Chưa kết nối — Vào Cài đặt Bluetooth TV → Quét thiết bị → chọn tên điện thoại này."
        try {
            discoverableLauncher.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                }
            )
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun showBondedDevicesDialog() {
        // ĐÃ BỎ hộp thoại "Lưu ý trước khi kết nối" theo yêu cầu - mở thẳng danh sách thiết bị
        // đã ghép nối, không còn cảnh báo chặn trước nữa.
        showBondedDevicesDialogInternal()
    }

    @SuppressLint("MissingPermission")
    private fun showBondedDevicesDialogInternal() {
        val bonded = hidManager.bondedDevices().toList()
        val scanLabel = "🔍  Quét thiết bị mới…"
        val items = (bonded.map { safeName(it) } + scanLabel).toTypedArray()
        val title = if (hidManager.isConnected) "Chọn lại thiết bị để nối kết" else "Chọn thiết bị để nối kết"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                proximityConnector?.disarm()
                if (which < bonded.size) hidManager.connectTo(bonded[which])
                else openScanDialog()
            }
            .show()
    }

    private fun openScanDialog() {
        val dialog = ScanDevicesDialog()
        dialog.callback = object : ScanDevicesDialog.Callback {
            override fun onDeviceSelected(device: BluetoothDevice) {
                proximityConnector?.disarm()
                hidManager.connectTo(device)
            }
        }
        dialog.show(supportFragmentManager, "scan_devices")
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice?): String {
        if (device == null) return "?"
        return try { device.name ?: device.address } catch (e: SecurityException) { device.address }
    }

    // ---------- Trackpad + 3 hàng nút ----------

    private fun setupTrackpad() {
        trackpad.onMove  = { dx, dy -> hidManager.sendMouseMove(dx, dy); syncInput.reset() }
        trackpad.onClick  = { rightButton -> hidManager.sendMouseClick(rightButton); syncInput.reset() }
        trackpad.onScroll = { dy -> hidManager.sendMouseScroll(dy); syncInput.reset() }
    }

    private fun setupNavRow() {
        findViewById<MaterialButton>(R.id.btnPickDevice).apply {
            setOnClickListener { showBondedDevicesDialog() }
        }
        findViewById<MaterialButton>(R.id.btnHome).setOnClickListener {
            hidManager.sendHome(); syncInput.reset()
        }
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener {
            hidManager.sendBack(); syncInput.reset()
        }
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

    /** Hàng D-pad: 4 phím Lên/Xuống/Trái/Phải, chức năng như phím cứng điều hướng
     *  trên remote thật (gửi HID keyboard arrow key, xem HidManager.sendDpad*()). */
    private fun setupDpadRow() {
        findViewById<MaterialButton>(R.id.btnDpadUp).setOnClickListener { hidManager.sendDpadUp() }
        findViewById<MaterialButton>(R.id.btnDpadDown).setOnClickListener { hidManager.sendDpadDown() }
        findViewById<MaterialButton>(R.id.btnDpadOk).setOnClickListener { hidManager.sendDpadOk() }
        findViewById<MaterialButton>(R.id.btnDpadLeft).setOnClickListener { hidManager.sendDpadLeft() }
        findViewById<MaterialButton>(R.id.btnDpadRight).setOnClickListener { hidManager.sendDpadRight() }
    }

    private fun setupMediaRow() {
        findViewById<MaterialButton>(R.id.btnRewind).setOnClickListener { hidManager.sendRewind() }
        findViewById<MaterialButton>(R.id.btnPreviousTrack).setOnClickListener { hidManager.sendPreviousTrack() }
        findViewById<MaterialButton>(R.id.btnPlayPause).setOnClickListener { hidManager.sendPlayPause() }
        findViewById<MaterialButton>(R.id.btnNextTrack).setOnClickListener { hidManager.sendNextTrack() }
        findViewById<MaterialButton>(R.id.btnFastForward).setOnClickListener { hidManager.sendFastForward() }
    }

    // ---------- Cửa sổ nổi ô gõ đồng bộ ----------

    /** Bật/tắt bàn phím ảo. Cửa sổ nổi hiện/ẩn theo. */
    private fun toggleVirtualKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (isInputBarVisible && isImeActuallyVisible) {
            // Đang mở bàn phím → đóng lại
            imm.hideSoftInputFromWindow(floatBar.editText.windowToken, 0)
            return
        }
        showFloatBar()
        floatBar.editText.requestFocus()
        imm.showSoftInput(floatBar.editText, InputMethodManager.SHOW_FORCED)
    }

    /** Hiện cửa sổ nổi ở vị trí phù hợp. */
    private fun showFloatBar() {
        isInputBarVisible = true
        if (fullscreenMode == FullscreenMode.MOUSE) return  // ẩn khi chuột toàn màn hình
        val offset = if (isImeActuallyVisible) imeHeightPx else rowsHeightPx
        floatBar.show(offset)
    }

    /** Ẩn cửa sổ nổi và ẩn bàn phím ảo. */
    private fun hideFloatBar() {
        isInputBarVisible = false
        floatBar.hide()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(floatBar.editText.windowToken, 0)
    }

    /** Lắng nghe sự kiện bàn phím ảo hệ thống để cập nhật vị trí cửa sổ nổi. */
    private fun setupKeyboardListener() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            // decorFitsSystemWindows đã tắt (edge-to-edge) nên phải tự chừa chỗ cho status bar
            // / thanh điều hướng hệ thống, nếu không nội dung sẽ bị đè lên bởi các thanh đó.
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            systemBarsBottomPx = systemBars.bottom

            val visibleNow = insets.isVisible(WindowInsetsCompat.Type.ime())
            isImeActuallyVisible = visibleNow
            // Trừ đi phần padding đáy đã áp cho rootContainer ở trên, vì bottomMargin của
            // floatBar được tính trong hệ toạ độ "trong padding", không phải từ mép màn hình.
            imeHeightPx = (insets.getInsets(WindowInsetsCompat.Type.ime()).bottom - systemBarsBottomPx)
                .coerceAtLeast(0)

            if (!visibleNow && isInputBarVisible && !voiceInput.isListening) {
                // Bàn phím ảo đóng lại (người dùng vuốt xuống) mà không phải mic
                // → ẩn cửa sổ nổi luôn
                isInputBarVisible = false
                floatBar.hide()
            } else if (isInputBarVisible) {
                // Cập nhật vị trí: trên bàn phím hoặc trên 3 hàng nút
                updateFloatBarPosition()
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootContainer)

        // Cache chiều cao 3 hàng nút sau khi layout xong
        rowNav.post { cacheRowsHeight() }
    }

    /** Đo chiều cao 3 hàng nút (tính từ đáy rootContainer) và cache lại. */
    private fun cacheRowsHeight() {
        if (fullscreenMode != FullscreenMode.NONE) return
        val rowNavLoc  = IntArray(2); rowNav.getLocationOnScreen(rowNavLoc)
        val rootLoc    = IntArray(2); rootContainer.getLocationOnScreen(rootLoc)
        // FIX: TRƯỚC ĐÂY trừ thêm 8dp (loweredByPx) ở đây để "cho ô gõ gần hàng nút hơn" -
        // nhưng làm vậy khiến ô gõ bị hạ THẤP HƠN mép trên của rowNav đúng 8dp, tức là ĐÈ LẤN
        // vào hàng nút đầu tiên (Home/Back/gear/bàn phím) thay vì đứng ngay phía trên nó. Bỏ
        // hẳn phần trừ này - ô gõ giờ đứng khít đúng mép trên rowNav, không còn chồng lên hàng
        // nút nữa.
        rowsHeightPx = (rootContainer.height - (rowNavLoc[1] - rootLoc[1])).coerceAtLeast(0)
        // Nếu đang hiện và không có bàn phím thật → cập nhật vị trí ngay
        if (isInputBarVisible && !isImeActuallyVisible) {
            floatBar.updateY(rowsHeightPx)
        }
        updateTrackpadBottomInset(floatBarTopFromBottomPx)
    }

    /** [topFromBottomPx]: mép trên của floatBar tính từ đáy rootContainer. Trackpad có
     *  đáy riêng của nó ngay TRÊN 3 hàng nút (cách đáy rootContainer đúng [rowsHeightPx]),
     *  nên phần floatBar "ăn" vào vùng trackpad = topFromBottomPx - rowsHeightPx (nếu > 0). */
    private fun updateTrackpadBottomInset(topFromBottomPx: Int) {
        floatBarTopFromBottomPx = topFromBottomPx
        val gapPx = (8 * resources.displayMetrics.density).toInt()
        trackpad.bottomInsetPx = (topFromBottomPx - rowsHeightPx + gapPx).coerceAtLeast(0)
    }

    /** Cập nhật vị trí Y của cửa sổ nổi theo trạng thái hiện tại. */
    private fun updateFloatBarPosition() {
        if (!isInputBarVisible) return
        if (fullscreenMode == FullscreenMode.MOUSE) return
        if (isImeActuallyVisible) {
            floatBar.updateY(imeHeightPx)
        } else {
            // Đo lại rowsHeight rồi cập nhật
            rowNav.post { cacheRowsHeight() }
        }
    }

    private fun pasteClipboard() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            toast("Clipboard trống, chưa copy văn bản nào")
            return
        }
        showFloatBar()
        floatBar.editText.requestFocus()
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
        showFloatBar()
        floatBar.editText.requestFocus()
        syncInput.ensureTrailingSpace()
        voiceStartPos = syncInput.cursorPosition()
        voiceInput.start()
        updateVoiceButtonVisualState(listening = true)
    }

    private fun onVoicePartialText(text: String) = syncInput.replaceFrom(voiceStartPos, text)

    private fun onVoiceStopped(sentEnter: Boolean) {
        updateVoiceButtonVisualState(listening = false)
        if (sentEnter) hidManager.sendSpecialKey("ENTER")
        // Sau khi mic dừng mà bàn phím ảo không hiện → cửa sổ nổi vẫn ở trên 3 hàng nút
        // (không ẩn: người dùng có thể muốn xem/sửa kết quả)
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

    // ---------- Khung ngắm góc-trên của trackpad: né topBar ----------

    /** topBar là lớp phủ riêng (không xếp tuần tự phía trên trackpad), nên phải tự đo
     *  chiều cao thật của nó rồi báo cho TrackpadView né ra — nếu không, 2 góc-trên của
     *  khung ngắm sẽ bị topBar đè lên mỗi khi thông báo dài hơn 1 dòng. */
    private fun setupTrackpadTopInset() {
        topBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) updateTrackpadTopInset()
        }
        topBar.post { updateTrackpadTopInset() }
    }

    private fun updateTrackpadTopInset() {
        val gapPx = (12 * resources.displayMetrics.density).toInt()
        trackpad.topInsetPx = if (topBar.visibility == View.VISIBLE) topBar.height + gapPx else gapPx
    }

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
        if (turningOn)
            Toast.makeText(this, "Đã bật chế độ $label toàn màn hình — nhấn đúp lại để tắt", Toast.LENGTH_LONG).show()
        else
            toast("Đã tắt chế độ $label toàn màn hình")
    }

    private fun applyFullscreenMode() {
        updateToggleButtonVisualState(btnMouseFullscreen, fullscreenMode == FullscreenMode.MOUSE)
        updateToggleButtonVisualState(btnKeyboardFullscreen, fullscreenMode == FullscreenMode.KEYBOARD)

        requestedOrientation = if (fullscreenMode == FullscreenMode.NONE)
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        applyLayoutState()

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        when (fullscreenMode) {
            FullscreenMode.KEYBOARD -> {
                showFloatBar()
                floatBar.editText.requestFocus()
                imm.showSoftInput(floatBar.editText, InputMethodManager.SHOW_FORCED)
            }
            FullscreenMode.MOUSE -> {
                floatBar.hide()   // ẩn khi chuột toàn màn hình
                imm.hideSoftInputFromWindow(floatBar.editText.windowToken, 0)
            }
            FullscreenMode.NONE -> {
                imm.hideSoftInputFromWindow(floatBar.editText.windowToken, 0)
                if (!isInputBarVisible) floatBar.hide()
            }
        }
    }

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

    // ---------- Layout chính (không còn liên quan syncInputBar nữa) ----------

    private fun applyLayoutState() {
        mainColumn.removeAllViews()
        (topBar.parent as? android.view.ViewGroup)?.removeView(topBar)

        val fillRemaining = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        rootContainer.addView(
            topBar, 1,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.TOP }
        )
        topBar.visibility = if (fullscreenMode == FullscreenMode.NONE) View.VISIBLE else View.GONE
        updateTrackpadTopInset()

        when (fullscreenMode) {
            FullscreenMode.MOUSE -> {
                trackpad.layoutParams = fillRemaining
                mainColumn.addView(trackpad)
            }
            FullscreenMode.KEYBOARD -> {
                // Chế độ bàn phím toàn màn hình: layout trống, cửa sổ nổi tự hiện
            }
            FullscreenMode.NONE -> {
                trackpad.layoutParams = fillRemaining
                mainColumn.addView(trackpad)
                mainColumn.addView(divider)
                mainColumn.addView(rowNav)
                mainColumn.addView(rowVolume)
                mainColumn.addView(rowDpad)
                mainColumn.addView(rowMedia)
                // Sau khi layout ổn định, đo lại chiều cao 4 hàng nút
                rowNav.post { cacheRowsHeight() }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun wasRegisteredBefore(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_HID_REGISTERED, false)

    private fun saveRegisteredState(registered: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_HID_REGISTERED, registered)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "btremote_prefs"
        private const val KEY_HID_REGISTERED = "hid_registered"
    }
}
