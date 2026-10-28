package com.wear.novelreader.transfer

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wear.novelreader.R
import com.wear.novelreader.databinding.ActivityWifiTransferBinding
import com.wear.novelreader.ui.EdgeBackTouchListener
import com.wear.novelreader.ui.WearStatusBar
import kotlinx.coroutines.launch

class WifiTransferActivity : ComponentActivity() {

    private lateinit var binding: ActivityWifiTransferBinding
    private lateinit var statusBar: WearStatusBar
    private var currentUrl: String? = null
    private var uploadExitDialog: AlertDialog? = null
    private var qrBitmap: Bitmap? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startServerService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusBar = WearStatusBar.attach(binding.root, binding.contentLayout)
        binding.root.setOnTouchListener(EdgeBackTouchListener(this))

        binding.toggleButton.setOnClickListener {
            val state = TransferController.state.value
            if (state.running || state.starting) {
                startService(Intent(this, TransferService::class.java).setAction(TransferService.ACTION_STOP))
            } else {
                startServerService()
            }
        }
        binding.copyButton.setOnClickListener { copyUrl() }
        binding.qrHeaderButton.setOnClickListener { showQr() }
        binding.qrLayer.setOnClickListener { hideQr() }
        binding.qrCard.setOnClickListener { }
        binding.wifiSettingsButton.setOnClickListener { openWifiSettings() }
        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.qrLayer.visibility == View.VISIBLE) {
                    hideQr()
                } else if (TransferController.state.value.activeUploads > 0) {
                    showUploadExitDialog()
                } else {
                    finish()
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TransferController.state.collect { render(it) }
            }
        }
        render(TransferController.state.value)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startServerService()
        }
    }

    override fun onStart() {
        super.onStart()
        startServerService()
    }

    override fun onStop() {
        hideQr()
        uploadExitDialog?.dismiss()
        super.onStop()
        if (!isChangingConfigurations) {
            stopService(Intent(this, TransferService::class.java))
        }
    }

    override fun onDestroy() {
        hideQr()
        uploadExitDialog?.dismiss()
        uploadExitDialog = null
        super.onDestroy()
    }

    private fun showUploadExitDialog() {
        if (isFinishing || isDestroyed || uploadExitDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wifi_uploading_title)
            .setMessage(R.string.wifi_uploading_exit_message)
            .setPositiveButton(R.string.wifi_exit_and_stop) { _, _ -> finish() }
            .setNegativeButton(R.string.wifi_continue_upload, null)
            .create()
        dialog.setOnDismissListener { uploadExitDialog = null }
        uploadExitDialog = dialog
        dialog.show()
    }

    private fun startServerService() {
        if (isFinishing || isDestroyed) return
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TransferService::class.java)
            )
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.wifi_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun render(state: TransferState) {
        currentUrl = state.urls.firstOrNull()
        when {
            state.error != null -> {
                binding.statusText.text = getString(R.string.wifi_error, state.error)
                binding.urlText.text = ""
                binding.toggleButton.text = getString(R.string.wifi_start)
            }
            state.starting -> {
                binding.statusText.text = getString(R.string.wifi_starting)
                binding.urlText.text = ""
                binding.toggleButton.text = getString(R.string.wifi_stop)
            }
            state.running && state.urls.isNotEmpty() -> {
                binding.statusText.text = getString(R.string.wifi_running)
                binding.urlText.text = state.urls.joinToString("\n")
                binding.toggleButton.text = getString(R.string.wifi_stop)
            }
            state.running -> {
                binding.statusText.text = getString(R.string.wifi_no_network)
                binding.urlText.text = getString(R.string.wifi_port, state.port)
                binding.toggleButton.text = getString(R.string.wifi_stop)
            }
            else -> {
                binding.statusText.text = getString(R.string.wifi_stopped)
                binding.urlText.text = ""
                binding.toggleButton.text = getString(R.string.wifi_start)
            }
        }
        binding.copyButton.isEnabled = !currentUrl.isNullOrBlank()
        binding.qrHeaderButton.isEnabled = !currentUrl.isNullOrBlank()
        if (currentUrl.isNullOrBlank() && binding.qrLayer.visibility == View.VISIBLE) {
            hideQr()
        }
        val received = if (state.uploads > 0) {
            getString(R.string.wifi_received, state.uploads)
        } else {
            ""
        }
        binding.progressText.text = received
        binding.progressText.visibility = if (received.isNotEmpty()) View.VISIBLE else View.GONE
        val lastUpload = state.lastUpload?.let {
            getString(R.string.wifi_last_upload, it)
        } ?: ""
        binding.receivedText.text = lastUpload
        binding.receivedText.visibility = if (lastUpload.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tipText.visibility =
            if (!state.running || state.urls.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun copyUrl() {
        val url = currentUrl ?: return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(this, R.string.wifi_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showQr() {
        val url = currentUrl ?: return
        val bitmap = try {
            QrCode.create(url)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.wifi_qr_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
            return
        }
        qrBitmap?.recycle()
        qrBitmap = bitmap
        binding.qrImage.setImageBitmap(bitmap)
        binding.qrUrl.text = url
        statusBar.setOverlayHidden(true)
        binding.qrLayer.bringToFront()
        binding.qrLayer.visibility = View.VISIBLE
    }

    private fun hideQr() {
        if (binding.qrLayer.visibility != View.VISIBLE) return
        binding.qrLayer.visibility = View.GONE
        binding.qrImage.setImageDrawable(null)
        qrBitmap?.recycle()
        qrBitmap = null
        statusBar.setOverlayHidden(false)
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }
}




