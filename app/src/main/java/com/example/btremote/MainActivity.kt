package com.example.btremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var hidManager: HidManager
    private lateinit var topBar: android.widget.FrameLayout
    private lateinit var statusText: TextView
    private lateinit var btnRegisterHid: MaterialButton
    private lateinit var trackpad: TrackpadView
    private lateinit var syncInputBar: LinearLayout
    private lateinit var syncInput: EditText

    // Bản ghi những gì app ĐÃ gửi thành công lên TV thông qua syncInput — dùng để
    // so sánh (diff) với nội dung mới mỗi khi syncInput đổi, từ đó chỉ gửi phần
    // KÝ TỰ THAY ĐỔI (thêm/xoá) thay vì gửi lại từ đầu mỗi lần.
    private var syncSentText = ""
    // true trong lúc code tự set text vào syncInput (không phải người dùng gõ/dán) —
    // dùng để bỏ qua watcher, tránh gửi trùng lặp lên TV.
    private var isSyncProgrammaticChange = false

    // Các thành phần dùng để tự sắp xếp lại layout (chuột / 3 phím / bàn phím ảo)
    private lateinit var mainColumn: LinearLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var volumeControls: LinearLayout
    private lateinit var mediaControls: LinearLayout
    private lateinit var divider: android.view.View
    private var isKeyboardVisible = false
    // Thời điểm gần nhất người dùng CHỦ ĐỘNG mở bàn phím (bấm nút ⌨ hoặc bật chế
    // độ Bàn phím toàn màn hình) — dùng để listener trong setupAutoLayout() bỏ
    // qua lần đọc chiều cao SAI ngay sau đó (bàn phím ảo thật sự chưa kịp trượt
    // lên, xem giải thích chi tiết ở setupAutoLayout()).
    private var lastManualKeyboardOpenAt = 0L

    // ---- Nhập liệu bằng giọng nói (nút micro) ----
    // Dùng thẳng SpeechRecognizer thay vì mở hộp thoại nhận diện giọng nói có sẵn
    // của hệ thống (ACTION_RECOGNIZE_SPEECH): cách cũ giao hết quyền kiểm soát
    // cho app nhận diện của máy (Google/OEM khác) — trên 1 số máy, hộp thoại đó
    // cứ nghe MÃI không tự dừng, không bao giờ trả kết quả về (đúng hiện tượng
    // "ghi hoài mà không viết ra chữ nào"). Dùng SpeechRecognizer trực tiếp cho
    // phép: (1) hiện chữ ra syncInput NGAY khi đang nói (kết quả tạm/partial),
    // (2) TỰ đếm 2 giây im lặng để chủ động dừng nghe + gửi ENTER, không phụ
    // thuộc vào việc máy có tự dừng đúng lúc hay không.
    private var speechRecognizer: SpeechRecognizer? = null
    private var isVoiceListening = false
    // Vị trí bắt đầu đoạn "đang nghe" trong syncInput — mỗi lần có kết quả tạm
    // mới, ta THAY THẾ từ vị trí này tới hết chuỗi bằng câu nhận diện mới nhất
    // (vì partial result là toàn bộ câu tính từ lúc bắt đầu nói, không phải chỉ
    // phần thêm), rồi để watcher có sẵn của syncInput tự lo gửi diff lên TV.
    private var voiceInputStartPos = 0
    private val silenceHandler = Handler(Looper.getMainLooper())
    private val silenceRunnable = Runnable { onVoiceSilenceTimeout() }
    private val VOICE_SILENCE_TIMEOUT_MS = 2000L

    // 2 nút nổi góc trên: bật/tắt chế độ toàn màn hình xoay ngang cho chuột / bàn phím.
    private lateinit var btnMouseFullscreen: MaterialButton
    private lateinit var btnKeyboardFullscreen: MaterialButton
    private enum class FullscreenMode { NONE, MOUSE, KEYBOARD }
    private var fullscreenMode = FullscreenMode.NONE

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

    // Launcher xin quyền RECORD_AUDIO cho nút micro: xin xong thì bắt đầu nghe luôn
    // nếu được cấp quyền.
    private val recordAudioPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListeningContinuous()
        } else {
            Toast.makeText(this, "Cần cấp quyền Micro để nhập liệu bằng giọng nói", Toast.LENGTH_LONG).show()
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
        syncInputBar = findViewById(R.id.syncInputBar)
        syncInput = findViewById(R.id.syncInput)
        mainColumn = findViewById(R.id.mainColumn)
        controlsRow = findViewById(R.id.controlsRow)
        volumeControls = findViewById(R.id.volumeControls)
        mediaControls = findViewById(R.id.mediaControls)
        divider = findViewById(R.id.divider)
        btnMouseFullscreen = findViewById(R.id.btnMouseFullscreen)
        btnKeyboardFullscreen = findViewById(R.id.btnKeyboardFullscreen)

        // 2 nút góc trên chỉ bật/tắt khi NHẤN ĐÚP — vì đặt gần vùng trackpad nên nếu
        // cho bấm 1 chạm sẽ rất dễ trúng nhầm lúc đang lướt ngón tay để di chuột.
        setupDoubleTapToggle(btnMouseFullscreen, FullscreenMode.MOUSE)
        setupDoubleTapToggle(btnKeyboardFullscreen, FullscreenMode.KEYBOARD)

        findViewById<Button>(R.id.btnVolumeUp).setOnClickListener { hidManager.sendVolumeUp() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { hidManager.sendSpecialKey("ESC") }
        findViewById<Button>(R.id.btnVolumeDown).setOnClickListener { hidManager.sendVolumeDown() }
        findViewById<Button>(R.id.btnScreenOff).setOnClickListener {
            hidManager.sendScreenOff()
            Toast.makeText(this, "Đã gửi lệnh tắt màn hình (chỉ hoạt động với TV)", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPreviousTrack).setOnClickListener { hidManager.sendPreviousTrack() }
        findViewById<Button>(R.id.btnPlayPause).setOnClickListener { hidManager.sendPlayPause() }
        findViewById<Button>(R.id.btnNextTrack).setOnClickListener { hidManager.sendNextTrack() }
        findViewById<Button>(R.id.btnRewind).setOnClickListener { hidManager.sendRewind() }
        findViewById<Button>(R.id.btnFastForward).setOnClickListener { hidManager.sendFastForward() }

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
                    statusText.text = "Chưa kết nối thiết bị nào — hãy chọn thiết bị để kết nối"
                    // Tự kết nối lại thiết bị đã dùng lần trước (nếu có) — không cần chọn lại.
                    hidManager.autoReconnectLastDevice()
                    // Đăng ký HID xong (tức là đã xin quyền + bật Bluetooth xong) —
                    // đúng lúc app đã sẵn sàng dùng, hiện hướng dẫn 2 icon góc trên
                    // (chỉ hiện 1 lần duy nhất, xem maybeShowFullscreenIconsHint()).
                    maybeShowFullscreenIconsHint()
                }
            }

            override fun onUnregistered() {
                runOnUiThread { setHidRegisteredUi(registered = false) }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, connected: Boolean) {
                runOnUiThread {
                    setHidRegisteredUi(registered = true)
                    statusText.text = if (connected)
                        "Đang kết nối tới: ${safeName(device)}"
                    else
                        "Chưa kết nối thiết bị nào"
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
            if (isKeyboardVisible) {
                // Đang mở -> bấm lại để ĐÓNG: chỉ ẩn bàn phím ảo hệ thống, KHÔNG
                // tự gọi applyLayoutState(false) ngay tại đây — để listener trong
                // setupAutoLayout() tự phát hiện bàn phím thật đã hạ xuống rồi mới
                // gọi applyLayoutState(false) đúng lúc. Nếu tự set false ngay ở
                // đây trong khi bàn phím thật còn đang trượt xuống (vài khung
                // hình), listener sẽ đọc thấy visibleNow vẫn true != isKeyboard-
                // Visible vừa set false -> hiểu lầm là "vừa MỞ lại" -> gọi
                // applyLayoutState(true) đè lên, làm nút bấm không đóng được /
                // nhấp nháy mở lại. Cùng cách xử lý với khi tắt chế độ bàn phím
                // toàn màn hình ở applyFullscreenMode().
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(syncInput.windowToken, 0)
                return@setOnClickListener
            }

            // QUAN TRỌNG: syncInputBar chỉ được gắn vào layout khi bàn phím ĐANG mở
            // (xem applyLayoutState) — nhưng lúc bấm nút này thì bàn phím CHƯA mở,
            // nên syncInput đang không nằm trong layout, requestFocus() sẽ vô tác
            // dụng nếu không gắn view vào trước. Chủ động gắn trước rồi mới focus.
            lastManualKeyboardOpenAt = System.currentTimeMillis()
            if (syncInputBar.parent == null) {
                applyLayoutState(keyboardVisible = true)
            }
            syncInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            // SHOW_FORCED thay vì SHOW_IMPLICIT: với IMPLICIT, hệ thống có thể ÂM
            // THẦM BỎ QUA yêu cầu mở nếu trước đó người dùng từng tự đóng bàn phím
            // (Android nhớ trạng thái "đã bị đóng thủ công" và không tự mở lại khi
            // gặp yêu cầu IMPLICIT) -> đây là 1 trong các nguyên nhân khiến bấm nút
            // này nhiều lần không lên bàn phím. FORCED luôn mở, bất kể trạng thái đó.
            imm.showSoftInput(syncInput, InputMethodManager.SHOW_FORCED)
        }

        // Tắt gợi ý/autocorrect để giảm tối đa việc IME (Gboard...) tự sửa/gộp từ,
        // giúp nội dung syncInput khớp sát nhất với những gì người dùng thật sự gõ.
        syncInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        setupSyncInput()

        findViewById<Button>(R.id.btnPasteClipboard).setOnClickListener { pasteClipboardIntoSyncInput() }
        findViewById<Button>(R.id.btnClearSyncInput).setOnClickListener { resetSyncInput() }
        findViewById<Button>(R.id.btnVoiceInput).setOnClickListener { startVoiceInput() }

        findViewById<Button>(R.id.keyHome).setOnClickListener {
            hidManager.sendSpecialKey("HOME")
            // Bấm Home = chắc chắn rời khỏi ô nhập đang gõ trên TV -> reset đồng bộ.
            resetSyncInput()
        }

        trackpad.onMove = { dx, dy ->
            hidManager.sendMouseMove(dx, dy)
            // Di chuột = có thể đang rời khỏi ô nhập trên TV -> reset đồng bộ.
            resetSyncInput()
        }
        trackpad.onClick = { rightButton ->
            hidManager.sendMouseClick(rightButton)
            resetSyncInput()
        }
        trackpad.onScroll = { dy ->
            hidManager.sendMouseScroll(dy)
            resetSyncInput()
        }

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
            // LỖI ĐÃ SỬA: ngay sau khi người dùng bấm nút mở bàn phím, ta CHỦ ĐỘNG
            // gắn syncInputBar + set isKeyboardVisible=true TRƯỚC KHI bàn phím ảo
            // thật sự trượt lên (quá trình đó mất vài khung hình). Nếu không có
            // "khoảng nghỉ" bên dưới, đúng khung hình đó listener sẽ đọc được
            // keypadHeight vẫn còn nhỏ (bàn phím ảo CHƯA kịp hiện) -> visibleNow=
            // false trong khi isKeyboardVisible vừa được set true ngay trước đó ->
            // tưởng lầm là bàn phím vừa bị đóng -> gọi applyLayoutState(false) THÁO
            // NGAY syncInputBar vừa gắn -> syncInput mất focus giữa chừng -> hệ
            // thống tự đóng luôn bàn phím ảo thật vì ô nhập liệu không còn focus.
            // Đây là nguyên nhân khiến nút "Mở bàn phím" bấm không lên / chớp lên
            // rồi tắt ngay. Bỏ qua các lần đọc "bàn phím vừa đóng" trong 600ms sau
            // 1 lần mở tay để bàn phím ảo thật có đủ thời gian trượt lên hẳn.
            val justOpenedManually = isKeyboardVisible &&
                System.currentTimeMillis() - lastManualKeyboardOpenAt < 600
            if (visibleNow != isKeyboardVisible && !(!visibleNow && justOpenedManually)) {
                applyLayoutState(visibleNow)
            }
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /**
     * Gắn watcher cho syncInput: mỗi khi nội dung đổi (do người dùng gõ trực tiếp
     * HOẶC dán/paste văn bản có dấu từ điện thoại), so sánh với [syncSentText] —
     * phần đầu giống nhau (common prefix) giữ nguyên, phần đuôi cũ bị mất thì gửi
     * BACKSPACE tương ứng, phần mới thêm vào thì gửi lên TV bằng typeText() (tự
     * chuyển Telex nếu có dấu). Nhờ vậy ô này vừa hiển thị đúng những gì ĐÃ gửi,
     * vừa hỗ trợ paste nguyên khối văn bản có dấu chỉ trong 1 lần gửi diff.
     */
    private fun setupSyncInput() {
        syncInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // Do chính code tự set (mirror/reset) -> bỏ qua, tránh gửi lặp lên TV.
                if (isSyncProgrammaticChange) return
                val newText = s?.toString() ?: ""
                if (newText == syncSentText) return

                var commonPrefix = 0
                val maxCommon = minOf(syncSentText.length, newText.length)
                while (commonPrefix < maxCommon &&
                    syncSentText[commonPrefix] == newText[commonPrefix]
                ) commonPrefix++

                val deleteCount = syncSentText.length - commonPrefix
                repeat(deleteCount) { hidManager.sendSpecialKey("BACKSPACE") }

                val added = newText.substring(commonPrefix)
                if (added.isNotEmpty()) hidManager.typeText(added)

                syncSentText = newText
            }
        })

        // Enter thật từ bàn phím ảo (một số IME gửi KeyEvent thay vì đổi nội dung text).
        syncInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                keyCode == android.view.KeyEvent.KEYCODE_ENTER
            ) {
                hidManager.sendSpecialKey("ENTER")
                true
            } else false
        }
        // Một số bàn phím ảo gửi nút "Enter/Go/Tìm kiếm" qua editor action thay vì KeyEvent.
        syncInput.setOnEditorActionListener { _, _, _ ->
            hidManager.sendSpecialKey("ENTER")
            true
        }
    }

    /**
     * Xoá trắng syncInput VÀ trạng thái "đã gửi" cục bộ — KHÔNG gửi bất kỳ phím
     * BACKSPACE nào lên TV. Dùng khi coi như ô nhập trên TV đã đổi/mất focus
     * (di chuột, bấm chuột, cuộn, bấm Home...): lúc này gửi backspace lên TV là
     * sai chỗ (xoá nhầm nội dung khác), nên chỉ cần đồng bộ lại từ đầu, coi như
     * "trang trắng" mới, không giả định biết nội dung ô nhập trên TV nữa.
     */
    private fun resetSyncInput() {
        if (syncSentText.isEmpty() && syncInput.text.isNullOrEmpty()) return
        isSyncProgrammaticChange = true
        syncInput.setText("")
        syncSentText = ""
        isSyncProgrammaticChange = false
    }

    /** Đọc văn bản (có thể có dấu) từ clipboard điện thoại, chèn vào syncInput tại
     *  vị trí con trỏ — watcher ở trên sẽ tự gửi phần mới thêm này lên TV. */
    private fun pasteClipboardIntoSyncInput() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "Clipboard trống, chưa copy văn bản nào", Toast.LENGTH_SHORT).show()
            return
        }
        val pasted = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        if (pasted.isEmpty()) return
        insertTextIntoSyncInput(pasted)
    }

    /** Chèn 1 đoạn văn bản (từ clipboard hoặc từ giọng nói đã nhận diện) vào
     *  syncInput tại vị trí con trỏ — watcher của syncInput sẽ tự lo gửi phần
     *  mới thêm này lên TV (tự chuyển Telex nếu có dấu, xem setupSyncInput()). */
    private fun insertTextIntoSyncInput(text: String) {
        if (syncInputBar.parent == null) applyLayoutState(keyboardVisible = true)
        syncInput.requestFocus()
        val start = syncInput.selectionStart.coerceAtLeast(0)
        val end = syncInput.selectionEnd.coerceAtLeast(0)
        syncInput.text?.replace(minOf(start, end), maxOf(start, end), text)
        syncInput.setSelection((minOf(start, end) + text.length).coerceAtMost(syncInput.text?.length ?: 0))
    }

    /** Bấm nút micro:
     *  - Nếu đang KHÔNG nghe: xin quyền RECORD_AUDIO nếu chưa có, có rồi thì bắt đầu nghe.
     *  - Nếu đang nghe: bấm lại để DỪNG NGHE SỚM ngay lập tức (coi như đã nói xong),
     *    không cần đợi đủ 2 giây im lặng — hữu ích nếu người dùng biết mình nói xong rồi. */
    private fun startVoiceInput() {
        if (isVoiceListening) {
            stopVoiceListening(finalizeWithEnter = true)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListeningContinuous()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Bắt đầu 1 phiên nghe bằng SpeechRecognizer (không phải hộp thoại hệ thống):
     *  kết quả TẠM (partial) sẽ được đổ thẳng vào syncInput NGAY khi đang nói —
     *  nhờ đó thấy chữ xuất hiện + gửi lên TV theo thời gian thực, không phải đợi
     *  "ghi xong" mới có chữ. Mỗi lần có kết quả tạm mới -> reset lại đồng hồ đếm
     *  2 giây; hết 2 giây mà KHÔNG có thêm kết quả mới (tức người dùng đã ngừng
     *  nói) -> tự dừng nghe + gửi phím ENTER lên TV (xem onVoiceSilenceTimeout()). */
    private fun startListeningContinuous() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Máy này không hỗ trợ nhận diện giọng nói", Toast.LENGTH_LONG).show()
            return
        }
        if (syncInputBar.parent == null) applyLayoutState(keyboardVisible = true)
        syncInput.requestFocus()
        voiceInputStartPos = syncInput.text?.length ?: 0

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        applyVoicePreview(text)
                        resetSilenceTimer()
                    }
                }

                override fun onResults(results: Bundle?) {
                    // Máy tự quyết định "nói xong" (hiếm khi xảy ra trước cả 2 giây của
                    // ta, nhưng có thể có trên 1 số máy) -> áp kết quả CUỐI rồi dừng
                    // hẳn + gửi ENTER ngay, không cần đợi thêm.
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrEmpty()) applyVoicePreview(text)
                    stopVoiceListening(finalizeWithEnter = true)
                }

                override fun onError(error: Int) {
                    // ERROR_NO_MATCH/ERROR_SPEECH_TIMEOUT: không nói gì cả -> dừng êm,
                    // không cần Toast làm phiền. Các lỗi khác thì báo cho biết.
                    if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        Toast.makeText(
                            this@MainActivity, "Lỗi nhận diện giọng nói (mã $error)", Toast.LENGTH_SHORT
                        ).show()
                    }
                    stopVoiceListening(finalizeWithEnter = false)
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Kéo dài các mốc thời gian tự-dừng CỦA MÁY ra khá lâu (4 giây) để đồng
            // hồ 2 giây CỦA TA luôn là bên quyết định dừng trước — tránh máy tự cắt
            // ngang giữa chừng câu nói sớm hơn ý người dùng.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
        }
        speechRecognizer?.startListening(intent)
        isVoiceListening = true
        updateVoiceButtonVisualState(true)
        resetSilenceTimer()
    }

    /** Thay đoạn "đang nghe" (từ voiceInputStartPos tới hết) bằng câu nhận diện
     *  mới nhất — watcher có sẵn của syncInput (xem setupSyncInput()) sẽ TỰ so
     *  sánh với những gì đã gửi và chỉ gửi phần khác biệt (backspace phần cũ bị
     *  sửa + gõ phần mới) lên TV, y hệt cơ chế paste/gõ tay bình thường. */
    private fun applyVoicePreview(text: String) {
        val editable = syncInput.text ?: return
        val end = editable.length
        val start = voiceInputStartPos.coerceAtMost(end)
        editable.replace(start, end, text)
        syncInput.setSelection(syncInput.text?.length ?: 0)
    }

    /** Huỷ đếm ngược cũ (nếu có) rồi đặt lại đồng hồ 2 giây mới — gọi mỗi khi có
     *  kết quả tạm mới (tức người dùng vẫn đang nói). */
    private fun resetSilenceTimer() {
        silenceHandler.removeCallbacks(silenceRunnable)
        silenceHandler.postDelayed(silenceRunnable, VOICE_SILENCE_TIMEOUT_MS)
    }

    /** Hết 2 giây mà không có kết quả tạm nào mới -> coi như người dùng đã NGỪNG
     *  NÓI -> dừng nghe + gửi phím ENTER lên TV (đúng yêu cầu: "dừng nói 2s thì
     *  nó enter"). */
    private fun onVoiceSilenceTimeout() {
        if (!isVoiceListening) return
        stopVoiceListening(finalizeWithEnter = true)
    }

    /** Dừng hẳn phiên nghe hiện tại: huỷ timer, huỷ SpeechRecognizer, cập nhật lại
     *  giao diện nút micro. [finalizeWithEnter] = true -> gửi thêm phím ENTER lên
     *  TV (dùng khi dừng do im lặng 2s HOẶC người dùng chủ động bấm dừng sớm);
     *  = false -> chỉ dừng, không gửi gì thêm (dùng khi lỗi/không nghe được gì). */
    private fun stopVoiceListening(finalizeWithEnter: Boolean) {
        silenceHandler.removeCallbacks(silenceRunnable)
        isVoiceListening = false
        updateVoiceButtonVisualState(false)
        speechRecognizer?.apply {
            stopListening()
            destroy()
        }
        speechRecognizer = null
        if (finalizeWithEnter) hidManager.sendSpecialKey("ENTER")
    }

    /** Đổi màu nút micro để báo hiệu đang nghe hay không — cùng cách đổi màu với
     *  2 nút toàn màn hình góc trên (xem updateToggleButtonVisualState()). */
    private fun updateVoiceButtonVisualState(listening: Boolean) {
        findViewById<MaterialButton>(R.id.btnVoiceInput).backgroundTintList = ColorStateList.valueOf(
            if (listening) 0xFFE53935.toInt() else 0xFF6750A4.toInt()
        )
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

    /**
     * Gắn nhận diện nhấn đúp cho nút góc trên: chỉ khi 2 lần chạm liên tiếp thật sự
     * (nhanh, gần nhau) mới bật/tắt chế độ toàn màn hình tương ứng — 1 chạm đơn lẻ
     * (kể cả vô tình quẹt trúng lúc đang kéo trackpad) sẽ bị bỏ qua, không kích hoạt.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDoubleTapToggle(button: MaterialButton, mode: FullscreenMode) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFullscreenMode(mode)
                return true
            }
        })
        button.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    /**
     * Bấm nút góc trên -> bật chế độ tương ứng (xoay ngang, toàn màn hình); bấm lại
     * chính nút đang bật -> tắt, quay về bình thường. Chỉ 1 chế độ hoạt động tại 1
     * thời điểm: bấm nút còn lại trong khi đang bật chế độ kia sẽ tự chuyển sang.
     */
    private fun toggleFullscreenMode(mode: FullscreenMode) {
        val turnedOn = fullscreenMode != mode
        fullscreenMode = if (fullscreenMode == mode) FullscreenMode.NONE else mode
        applyFullscreenMode()

        // Thông báo ngắn mỗi lần nhấn đúp — người dùng biết ngay là đã bật/tắt
        // đúng chế độ, tránh nhấn đúp mà không rõ có ăn hay không (đặc biệt khi
        // đang ở gần trackpad, dễ nhầm với thao tác lướt chuột).
        val label = if (mode == FullscreenMode.MOUSE) "chuột" else "bàn phím"
        Toast.makeText(
            this,
            if (turnedOn) "Đã bật chế độ $label toàn màn hình — nhấn đúp lại để tắt"
            else "Đã tắt chế độ $label toàn màn hình",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Hướng dẫn ngắn cho 2 nút tròn góc trên (🖱 chuột / ⌨ bàn phím) — chỉ hiện
     * ĐÚNG 1 LẦN (lần đầu app đăng ký HID thành công), nhớ bằng SharedPreferences
     * để những lần mở app sau không bị hiện lại làm phiền.
     */
    private fun maybeShowFullscreenIconsHint() {
        val prefs = getSharedPreferences("btremote_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("hint_fullscreen_icons_shown", false)) return
        prefs.edit().putBoolean("hint_fullscreen_icons_shown", true).apply()

        AlertDialog.Builder(this)
            .setTitle("Mẹo nhỏ")
            .setMessage(
                "2 nút tròn ở góc trên màn hình:\n" +
                    "🖱 (trái) — chế độ chuột toàn màn hình, xoay ngang\n" +
                    "⌨ (phải) — chế độ bàn phím toàn màn hình, xoay ngang\n\n" +
                    "Nhấn ĐÚP (2 lần liên tiếp thật nhanh) vào nút để bật/tắt. " +
                    "Nhấn 1 lần sẽ không có tác dụng gì — tránh bấm nhầm khi đang lướt ngón tay trên trackpad."
            )
            .setPositiveButton("Đã hiểu", null)
            .show()
    }

    private fun applyFullscreenMode() {
        updateToggleButtonVisualState(btnMouseFullscreen, fullscreenMode == FullscreenMode.MOUSE)
        updateToggleButtonVisualState(btnKeyboardFullscreen, fullscreenMode == FullscreenMode.KEYBOARD)

        // Chế độ chuột hoặc bàn phím toàn màn hình đều xoay ngang; tắt cả 2 thì trả
        // lại cho hệ thống/khoá xoay của máy tự quyết định như bình thường.
        requestedOrientation = if (fullscreenMode == FullscreenMode.NONE)
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // QUAN TRỌNG: phải gắn layout TRƯỚC (để syncInputBar/syncInput thật sự nằm
        // trong cây view) rồi mới request focus + mở bàn phím ảo — nếu làm ngược
        // lại, syncInput lúc đó chưa được gắn vào nên requestFocus() vô tác dụng,
        // bàn phím sẽ không mở được.
        if (fullscreenMode == FullscreenMode.KEYBOARD) {
            // Ghi nhận mốc thời gian TRƯỚC applyLayoutState() để listener trong
            // setupAutoLayout() có "khoảng nghỉ" bỏ qua lần đọc chiều cao sai ngay
            // sau đó (bàn phím ảo thật sự chưa kịp trượt lên) — xem chi tiết ở đó.
            lastManualKeyboardOpenAt = System.currentTimeMillis()
        }
        applyLayoutState(isKeyboardVisible)

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (fullscreenMode == FullscreenMode.KEYBOARD) {
            syncInput.requestFocus()
            // SHOW_FORCED: xem giải thích ở nút "Mở bàn phím" phía trên — IMPLICIT
            // có thể bị hệ thống âm thầm bỏ qua nếu người dùng từng tự đóng bàn
            // phím trước đó.
            imm.showSoftInput(syncInput, InputMethodManager.SHOW_FORCED)
        } else {
            imm.hideSoftInputFromWindow(syncInput.windowToken, 0)
        }
    }

    /** Đổi màu nút góc trên để báo hiệu chế độ đang BẬT hay tắt. */
    private fun updateToggleButtonVisualState(button: MaterialButton, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(
            if (active) 0xFF4FC3F7.toInt() else 0x40000000
        )
    }

    private fun applyLayoutState(keyboardVisible: Boolean) {
        isKeyboardVisible = keyboardVisible
        mainColumn.removeAllViews()

        val fillRemainingSpace = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )

        when (fullscreenMode) {
            FullscreenMode.MOUSE -> {
                // Chỉ còn trackpad, chiếm toàn bộ màn hình; ẩn tạm thanh trạng thái + 3 phím.
                trackpad.layoutParams = fillRemainingSpace
                mainColumn.addView(trackpad)
            }
            FullscreenMode.KEYBOARD -> {
                // Bỏ hẳn khối chữ hướng dẫn to (chỉ tốn chỗ, không có tác dụng) —
                // cho thẳng syncInputBar chiếm HẾT phần trống phía trên bàn phím ảo,
                // vừa hiển thị nhiều chữ hơn vừa không còn khoảng trống vô ích.
                syncInputBar.layoutParams = fillRemainingSpace
                syncInput.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                mainColumn.addView(syncInputBar)
            }
            FullscreenMode.NONE -> {
                // Thứ tự CỐ ĐỊNH: thanh trạng thái -> chuột (co giãn) -> 3 phím dưới cùng.
                // 3 phím không còn tự nhảy lên trên khi bàn phím ảo hệ thống mở nữa —
                // nếu bàn phím ảo che khuất chúng thì cứ để bị che, người dùng đóng
                // bàn phím ảo lại là thấy ngay, không cần layout tự sắp xếp lại.
                mainColumn.addView(topBar)
                trackpad.layoutParams = fillRemainingSpace
                mainColumn.addView(trackpad)
                mainColumn.addView(divider)
                mainColumn.addView(controlsRow)
                mainColumn.addView(volumeControls)
                mainColumn.addView(mediaControls)
                // Thanh nhập liệu đồng bộ CHỈ xuất hiện cùng lúc với bàn phím ảo,
                // và luôn là view cuối cùng -> tự nằm ngay trên bàn phím. Ở đây trả
                // về kích cỡ gọn (thanh mỏng 1 dòng) thay vì chiếm hết chỗ như chế
                // độ KEYBOARD toàn màn hình, vì bên trên vẫn còn trackpad cần dùng.
                syncInputBar.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                syncInput.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                if (keyboardVisible) mainColumn.addView(syncInputBar)
            }
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
        silenceHandler.removeCallbacks(silenceRunnable)
        speechRecognizer?.destroy()
        hidManager.unregister()
        super.onDestroy()
    }
}
