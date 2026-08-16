package com.example.btremote

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bắt mọi lỗi (exception) chưa được xử lý làm app văng ra ngoài, lưu lại toàn bộ
 * stack trace vào SharedPreferences trước khi để hệ thống đóng app như bình thường.
 * Lần mở app kế tiếp, [consumeLastCrash] đọc và xoá log này đi — MainActivity dùng
 * nó để hiện dialog lỗi lên TRƯỚC khi làm bất cứ việc gì khác, giúp biết ngay app
 * vừa văng vì lý do gì mà không cần cắm dây xem logcat.
 */
object CrashHandler {

    private const val PREFS = "bt_remote_prefs"
    private const val KEY_LAST_CRASH = "last_crash_trace"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                val report = "Thời điểm: $time\nThread: ${thread.name}\n\n$sw"

                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, report)
                    .apply()
            } catch (_: Throwable) {
                // Không để việc ghi log crash lại gây thêm lỗi khác.
            }
            // Giao lại cho handler mặc định của hệ thống để app đóng lại như bình thường.
            previousHandler?.uncaughtException(thread, throwable)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** Đọc log lỗi lần văng gần nhất (nếu có) rồi xoá đi luôn — chỉ hiện được 1 lần. */
    fun consumeLastCrash(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trace = prefs.getString(KEY_LAST_CRASH, null) ?: return null
        prefs.edit().remove(KEY_LAST_CRASH).apply()
        return trace
    }
}
