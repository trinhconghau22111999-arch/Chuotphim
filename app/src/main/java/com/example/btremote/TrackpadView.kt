package com.example.btremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Vùng cảm ứng mô phỏng trackpad:
 *  - 1 ngón kéo  -> di chuyển chuột (gửi delta)
 *  - chạm nhẹ    -> click trái
 *  - giữ lâu     -> click phải
 *  - 2 ngón kéo dọc -> cuộn trang
 *
 * Dòng gợi ý cách dùng chỉ hiện cho tới khi người dùng thực sự KÉO/LƯỚT ngón tay
 * để di chuyển con trỏ chuột lần đầu tiên (không tính chạm/tap hay giữ để click
 * phải), sau đó biến mất vĩnh viễn (trạng thái được lưu lại nên mở app lại cũng
 * không hiện nữa).
 */
class TrackpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMove: ((dx: Int, dy: Int) -> Unit)? = null
    var onClick: ((rightButton: Boolean) -> Unit)? = null
    var onScroll: ((dy: Int) -> Unit)? = null

    /** Khoảng cách (px) cần né ở đỉnh cho 2 góc-trên của khung ngắm, để không bị đè bởi
     *  topBar (topBar giờ là lớp phủ riêng, không xếp tuần tự phía trên trackpad nữa nên
     *  trackpad thực chất trải dài từ đỉnh màn hình). MainActivity cập nhật giá trị này
     *  mỗi khi chiều cao topBar đổi (thông báo dài/ngắn khác nhau). */
    var topInsetPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Tương tự [topInsetPx] nhưng cho 2 góc-dưới: khoảng cách (px) cần né thêm phía
     *  đáy để không bị ô gõ đồng bộ nổi (FloatingSyncBar, "Đang gõ trên TV...") đè lên
     *  khi nó đang hiện ngay phía trên 3 hàng nút. */
    var bottomInsetPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val prefs = context.getSharedPreferences("bt_remote_prefs", Context.MODE_PRIVATE)
    private var hintDismissed = prefs.getBoolean(PREF_HINT_DISMISSED, false)

    private val paint = Paint().apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 36f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    // 4 viền góc kiểu khung ngắm (như ảnh người dùng vẽ tay) — kích thước và độ dày
    // luôn tính theo tỉ lệ % kích thước trackpad hiện tại (xem cornerLen/cornerMargin
    // trong onDraw), nên tự co dãn đúng theo độ lớn của trackpad, không bị cố định cứng.
    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val cornerPath = android.graphics.Path()

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var pointerCount = 0

    private val longPressThresholdMs = 450L
    private val tapSlop = 18f
    private val longPressRunnable = Runnable {
        if (!moved && pointerCount == 1) {
            onClick?.invoke(true) // long press = click phải
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            moved = true // tránh bắn thêm click trái khi nhấc tay
        }
    }

    /** Gọi khi người dùng vừa kéo/lướt ngón tay để di chuyển chuột lần đầu -> ẩn gợi ý vĩnh viễn. */
    private fun dismissHintIfNeeded() {
        if (!hintDismissed) {
            hintDismissed = true
            prefs.edit().putBoolean(PREF_HINT_DISMISSED, true).apply()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        drawCornerBrackets(canvas)
        if (!hintDismissed) {
            canvas.drawText(
                "Trackpad — kéo để di chuyển, chạm = click trái,",
                width / 2f, height / 2f - 20, textPaint
            )
            canvas.drawText(
                "giữ = click phải, 2 ngón kéo dọc = cuộn",
                width / 2f, height / 2f + 30, textPaint
            )
        }
    }

    /** 4 viền góc kiểu khung ngắm ở 4 góc trackpad. Mọi kích thước (độ dài cạnh góc,
     *  khoảng cách lề, độ dày nét) đều tính theo % chiều nhỏ nhất của view -> co dãn
     *  đúng theo độ lớn trackpad thực tế trên từng máy/mỗi lần xoay màn hình.
     *  2 góc trên được hạ xuống thêm [topInsetPx] (chiều cao topBar thực tế + khoảng hở)
     *  để luôn nằm rõ bên dưới khối thông báo, dù thông báo dài hay ngắn.
     *
     *  Ở MÀN HÌNH NGANG: margin ngang (marginX) tính theo % chiều nhỏ nhất (là chiều CAO
     *  lúc này) nên rất nhỏ, không đủ né 2 nút tròn nổi chuột/bàn phím (44dp + margin 8dp
     *  = ~52dp, cố định vị trí top|start/top|end trong activity_main.xml, KHÔNG đổi). Cộng
     *  thêm landscapeExtraMarginPx vào marginX (chỉ khi ngang) để 2 góc-trái dịch vào phải
     *  và 2 góc-phải dịch vào trái, thoát khỏi vùng 2 nút đó - không ảnh hưởng margin dọc
     *  hay chiều dài cạnh góc. */
    private fun drawCornerBrackets(canvas: Canvas) {
        val shortSide = minOf(width, height).toFloat()
        if (shortSide <= 0f) return
        val cornerLen = shortSide * 0.10f
        val marginY = shortSide * 0.05f
        cornerPaint.strokeWidth = (shortSide * 0.012f).coerceAtLeast(3f)

        val isLandscape = width > height
        val landscapeExtraMarginPx = if (isLandscape) 44f * resources.displayMetrics.density else 0f
        val marginX = marginY + landscapeExtraMarginPx

        val w = width.toFloat()
        val h = height.toFloat()
        val topOffset = topInsetPx.toFloat()
        val bottomOffset = bottomInsetPx.toFloat()

        // Trên-trái (hạ xuống topOffset)
        cornerPath.reset()
        cornerPath.moveTo(marginX, topOffset + cornerLen)
        cornerPath.lineTo(marginX, topOffset)
        cornerPath.lineTo(marginX + cornerLen, topOffset)
        canvas.drawPath(cornerPath, cornerPaint)

        // Trên-phải (hạ xuống topOffset)
        cornerPath.reset()
        cornerPath.moveTo(w - marginX - cornerLen, topOffset)
        cornerPath.lineTo(w - marginX, topOffset)
        cornerPath.lineTo(w - marginX, topOffset + cornerLen)
        canvas.drawPath(cornerPath, cornerPaint)

        // Dưới-trái (nâng lên bottomOffset)
        cornerPath.reset()
        cornerPath.moveTo(marginX, h - marginY - bottomOffset - cornerLen)
        cornerPath.lineTo(marginX, h - marginY - bottomOffset)
        cornerPath.lineTo(marginX + cornerLen, h - marginY - bottomOffset)
        canvas.drawPath(cornerPath, cornerPaint)

        // Dưới-phải (nâng lên bottomOffset)
        cornerPath.reset()
        cornerPath.moveTo(w - marginX - cornerLen, h - marginY - bottomOffset)
        cornerPath.lineTo(w - marginX, h - marginY - bottomOffset)
        cornerPath.lineTo(w - marginX, h - marginY - bottomOffset - cornerLen)
        canvas.drawPath(cornerPath, cornerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerCount = 1
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                moved = false
                postDelayed(longPressRunnable, longPressThresholdMs)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
                removeCallbacks(longPressRunnable)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    // 2 ngón: cuộn theo trục Y, dùng ngón đầu tiên làm mốc
                    val dy = event.getY(0) - lastY
                    if (Math.abs(dy) > 2) {
                        onScroll?.invoke(dy.toInt())
                        lastY = event.getY(0)
                    }
                    moved = true
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (Math.abs(event.x - downX) > tapSlop || Math.abs(event.y - downY) > tapSlop) {
                        moved = true
                        removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        // Đây mới thực sự là thao tác "kéo/lướt để di chuyển chuột" -> ẩn gợi ý.
                        dismissHintIfNeeded()
                        onMove?.invoke((dx * 1.5f).toInt(), (dy * 1.5f).toInt())
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                pointerCount = (event.pointerCount - 1).coerceAtLeast(0)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                val elapsed = System.currentTimeMillis() - downTime
                if (!moved && pointerCount <= 1 && elapsed < longPressThresholdMs) {
                    onClick?.invoke(false) // tap nhanh = click trái
                }
                pointerCount = 0
                moved = false
            }
        }
        return true
    }

    companion object {
        private const val PREF_HINT_DISMISSED = "trackpad_hint_dismissed"
    }
}
