package com.example.btremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialog quét thiết bị Bluetooth mới ngay trong app.
 * - Tự gọi startDiscovery() khi mở
 * - Realtime: thiết bị tìm thấy hiện ra ngay
 * - Bấm vào thiết bị: tự pair nếu chưa pair, rồi callback về MainActivity để connectTo()
 */
class ScanDevicesDialog : DialogFragment() {

    interface Callback {
        fun onDeviceSelected(device: BluetoothDevice)
    }

    var callback: Callback? = null

    private val foundDevices = mutableListOf<BluetoothDevice>()
    private val foundAddresses = mutableSetOf<String>()
    private lateinit var listContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private var isReceiverRegistered = false
    // Receiver theo dõi kết quả ghép nối (BOND_STATE_CHANGED), tạo động trong
    // connectDevice() khi thiết bị chưa pair. Giữ tham chiếu ở đây để có thể
    // unregister trong stopScan() nếu dialog bị đóng giữa lúc đang ghép nối dở
    // (trước đây không gỡ, có thể rò rỉ receiver nếu không bao giờ nhận được
    // broadcast BOND_BONDED/BOND_NONE).
    private var pairReceiver: BroadcastReceiver? = null

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device ?: return
                    val addr = device.address ?: return
                    if (addr in foundAddresses) return
                    foundAddresses.add(addr)
                    foundDevices.add(device)
                    addDeviceRow(device)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    progressBar.visibility = View.GONE
                    tvStatus.text = if (foundDevices.isEmpty())
                        "Không tìm thấy thiết bị nào. Đảm bảo thiết bị ở chế độ có thể ghép nối."
                    else
                        "Quét xong — tìm thấy ${foundDevices.size} thiết bị"
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 8)
        }

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(32, 0, 32, 8) }
        }
        root.addView(progressBar)

        tvStatus = TextView(ctx).apply {
            text = "Đang quét thiết bị Bluetooth lân cận..."
            textSize = 13f
            setPadding(48, 0, 48, 16)
            setTextColor(context.getColor(R.color.text_hint))
        }
        root.addView(tvStatus)

        listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(ctx, 300)
        ))

        return MaterialAlertDialogBuilder(ctx)
            .setTitle("Quét thiết bị mới")
            .setView(root)
            .setNegativeButton("Đóng") { _, _ -> stopScan() }
            .create()
    }

    @SuppressLint("MissingPermission")
    private fun addDeviceRow(device: BluetoothDevice) {
        val ctx = context ?: return
        val name = try { device.name?.takeIf { it.isNotBlank() } } catch (e: SecurityException) { null }
            ?: device.address
        val alreadyBonded = device.bondState == BluetoothDevice.BOND_BONDED

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 20)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x20FFFFFF),
                null, null
            )
            isClickable = true
            isFocusable = true
        }

        val tvName = TextView(ctx).apply {
            text = name
            textSize = 15f
            setTextColor(ctx.getColor(R.color.text_on_surface))
        }
        row.addView(tvName)

        val tvAddr = TextView(ctx).apply {
            text = device.address + if (alreadyBonded) "  ✓ Đã ghép nối" else ""
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_hint))
        }
        row.addView(tvAddr)

        row.setOnClickListener {
            connectDevice(device, tvAddr)
        }

        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(48, 0, 48, 0) }
            setBackgroundColor(0x20808080)
        }

        listContainer.addView(row)
        listContainer.addView(divider)
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice, statusView: TextView) {
        val bondState = try { device.bondState } catch (e: SecurityException) { BluetoothDevice.BOND_NONE }

        if (bondState == BluetoothDevice.BOND_BONDED) {
            // Đã pair rồi → connect HID thẳng
            statusView.text = device.address + "  → Đang kết nối..."
            callback?.onDeviceSelected(device)
            dismiss()
        } else {
            // Chưa pair → tự khởi động pair ngay trong app
            statusView.text = device.address + "  → Đang ghép nối..."
            // Lắng nghe kết quả pair
            val receiverForPairing = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d?.address != device.address) return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> {
                            unregisterPairReceiver()
                            // Pair xong → connect HID
                            activity?.runOnUiThread {
                                statusView.text = device.address + "  ✓ Đã ghép nối → Đang kết nối..."
                                callback?.onDeviceSelected(device)
                                dismiss()
                            }
                        }
                        BluetoothDevice.BOND_NONE -> {
                            unregisterPairReceiver()
                            activity?.runOnUiThread {
                                statusView.text = device.address + "  ✗ Ghép nối thất bại"
                            }
                        }
                    }
                }
            }
            // Nếu trước đó còn 1 receiver ghép nối khác chưa dọn (vd bấm ghép nối
            // 2 thiết bị liên tiếp) thì gỡ nó trước, tránh rò rỉ.
            unregisterPairReceiver()
            pairReceiver = receiverForPairing
            requireContext().registerReceiver(
                receiverForPairing,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            )
            try {
                device.createBond()
            } catch (e: SecurityException) {
                statusView.text = device.address + "  ✗ Không có quyền ghép nối"
                unregisterPairReceiver()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStart() {
        super.onStart()
        startScan()
    }

    override fun onStop() {
        super.onStop()
        stopScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        requireContext().registerReceiver(receiver, filter)
        isReceiverRegistered = true

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        adapter?.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (isReceiverRegistered) {
            try { requireContext().unregisterReceiver(receiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        // Dọn luôn receiver đang chờ kết quả ghép nối (nếu có) — dialog có thể bị
        // đóng (onStop) trước khi nhận được broadcast BOND_BONDED/BOND_NONE, nếu
        // không gỡ ở đây thì receiver này rò rỉ tới khi Activity/App bị huỷ.
        unregisterPairReceiver()
        try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}
    }

    private fun unregisterPairReceiver() {
        val current = pairReceiver ?: return
        pairReceiver = null
        try { context?.unregisterReceiver(current) } catch (_: Exception) {}
    }

    private fun dpToPx(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).toInt()
}
