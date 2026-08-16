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

    private val prefs = context.getSharedPreferences("bt_remote_prefs", Context.MODE_PRIVATE)
    private var hintDismissed = prefs.getBoolean(PREF_HINT_DISMISSED, false)

    private val paint = Paint().apply {
        // Nhạt hơn 1 chút so với #333333 cũ, để phân biệt rõ với khối nút
        // phía dưới (key_rows_background = #262626).
        color = Color.parseColor("#3D3D3D")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.parseColor("#888888")
        textSize = 36f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

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
