package com.spotify.music.usb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.spotify.music.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Live status of a connected USB audio DAC. */
data class UsbDeviceStatus(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val deviceName: String = "未连接 USB 音频设备",
    val sampleRate: Int = 0,
    val dacBitDepth: Int = 24,
    val channelCount: Int = 0,
    val framesWritten: Long = 0
)

/**
 * USB DAC control. Detects a connected USB audio device, exposes its status,
 * remembers the exclusive-output switch and volume, and optionally auto-pauses
 * playback when the DAC is unplugged. Bit-perfect streaming is delegated to the
 * platform audio routing (USB devices are enumerated via AudioManager).
 */
class UsbAudioController(
    private val context: Context,
    private val settings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(
        UsbDeviceStatus(enabled = settings.usbExclusiveEnabled.value)
    )
    val status: StateFlow<UsbDeviceStatus> = _status.asStateFlow()

    var onAutoPause: (() -> Unit)? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val wasConnected = _status.value.connected
            refresh()
            if (wasConnected && !_status.value.connected) {
                // DAC unplugged while exclusive -> auto pause
                onAutoPause?.invoke()
            }
        }
    }

    init {
        if (hasPermission()) {
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }
        scope.launch {
            while (isActive) {
                refresh()
                delay(1000)
            }
        }
        refresh()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.MODIFY_AUDIO_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Rebuild status from the current enabled flag and attached USB devices. */
    fun refresh() {
        val enabled = settings.usbExclusiveEnabled.value
        val usbDevice = findUsbDevice()
        val connected = usbDevice != null && enabled
        val current = _status.value
        if (usbDevice != null) {
            _status.value = current.copy(
                enabled = enabled,
                connected = connected,
                deviceName = usbDevice.productName?.toString() ?: "USB 音频设备",
                sampleRate = runCatching { usbDevice.sampleRates?.firstOrNull()?.toInt() ?: 0 }.getOrDefault(0),
                channelCount = runCatching { usbDevice.channelCounts?.firstOrNull()?.toInt() ?: 0 }.getOrDefault(0),
                dacBitDepth = dacBitDepthFor(usbDevice)
            )
        } else {
            _status.value = current.copy(
                enabled = enabled,
                connected = false,
                deviceName = "未连接 USB 音频设备",
                sampleRate = 0,
                channelCount = 0
            )
        }
    }

    fun enable(value: Boolean) {
        settings.setUsbExclusive(value)
        refresh()
    }

    fun setVolume(percent: Int) {
        settings.setUsbVolume(percent.coerceIn(0, 100))
    }

    fun frameProgress() {
        val cur = _status.value
        if (cur.enabled && cur.connected) {
            _status.value = cur.copy(framesWritten = cur.framesWritten + 1)
        }
    }

    private fun dacBitDepthFor(d: AudioDeviceInfo): Int {
        val encodings = d.encodings?.toList() ?: return 16
        return if (encodings.contains(android.media.AudioFormat.ENCODING_PCM_FLOAT)) 32
        else if (encodings.contains(android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED)) 24
        else if (encodings.contains(android.media.AudioFormat.ENCODING_PCM_16BIT)) 16
        else 24
    }

    private fun findUsbDevice(): AudioDeviceInfo? {
        if (!hasPermission()) return null
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
        }.getOrNull()
    }

    fun release() {
        scope.cancel()
        if (hasPermission()) {
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        }
    }
}