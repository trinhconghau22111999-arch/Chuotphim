package com.example.btremote

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

/**
 * Ô gõ đồng bộ ("Đang gõ trên TV") dưới dạng view nổi.
 *
 * View được add vào rootContainer (FrameLayout gốc của Activity) MỘT LẦN DUY
 * NHẤT trong onCreate và không bao giờ bị remove/re-add — tránh hoàn toàn lỗi
 * focus/IME do add/remove liên tục. Vị trí điều chỉnh bằng bottomMargin.
 *
 * Vị trí (bottomMargin tính từ đáy rootContainer):
 *   - Bàn phím ảo đang hiện → imeHeightPx  (ngay trên bàn phím)
 *   - Mic / không bàn phím  → rowsHeightPx (ngay trên 3 hàng nút)
 */
class FloatingSyncBar(
    private val activity: Activity,
    private val rootContainer: FrameLayout,
    private val onPaste: () -> Unit,
    private val onClear: () -> Unit,
) {
    val editText: EditText
    private val root: LinearLayout
    private val btnPaste: MaterialButton
    private val btnClear: MaterialButton

    /** Gọi mỗi khi vị trí/kích thước thực tế của thanh này thay đổi, báo khoảng cách
     *  (px) từ đáy màn hình lên tới MÉP TRÊN của thanh — để TrackpadView né 2 góc-dưới
     *  của khung ngắm ra khỏi vùng bị thanh này che (0 nếu đang ẩn). */
    var onLayoutChanged: ((topFromBottomPx: Int) -> Unit)? = null

    init {
        root = LayoutInflater.from(activity)
            .inflate(R.layout.floating_sync_bar, rootContainer, false) as LinearLayout

        editText = root.findViewById(R.id.floatSyncInput)
        btnPaste = root.findViewById(R.id.floatBtnPaste)
        btnClear = root.findViewById(R.id.floatBtnClear)

        btnPaste.setOnClickListener { onPaste() }
        btnClear.setOnClickListener { onClear() }

        // Add vào rootContainer một lần, mặc định GONE
        rootContainer.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = 0
            }
        )
        root.visibility = View.GONE

        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (root.visibility == View.VISIBLE && (top != oldTop || bottom != oldBottom)) {
                val bottomMargin = (root.layoutParams as FrameLayout.LayoutParams).bottomMargin
                onLayoutChanged?.invoke(bottomMargin + root.height)
            }
        }
    }

    /** Hiện ô gõ, ghim [bottomPx] px tính từ đáy. */
    fun show(bottomPx: Int = 0) {
        val lp = root.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = bottomPx
        root.layoutParams = lp
        root.visibility = View.VISIBLE
    }

    /** Ẩn ô gõ. */
    fun hide() {
        root.visibility = View.GONE
        onLayoutChanged?.invoke(0)
    }

    /** Cập nhật vị trí Y mà không thay đổi visibility. */
    fun updateY(bottomPx: Int) {
        if (root.visibility != View.VISIBLE) return
        val lp = root.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin == bottomPx) return
        lp.bottomMargin = bottomPx
        root.layoutParams = lp
        onLayoutChanged?.invoke(bottomPx + root.height)
    }

    val isVisible get() = root.visibility == View.VISIBLE
}
