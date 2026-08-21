package com.rosergenbg.logo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** A selectable Android audio endpoint. A null device means "let Android choose". */
data class AudioDeviceOption(
    val device: AudioDeviceInfo?,
    val label: String
) {
    override fun toString(): String = label
}

class AudioRouteManager(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun inputDevices(): List<AudioDeviceOption> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .sortedWith(compareBy<AudioDeviceInfo> { inputPriority(it) }.thenBy { it.productName.toString() })
        return listOf(AudioDeviceOption(null, "Автоматически (система)")) + devices.map { option(it) }
    }

    fun outputDevices(): List<AudioDeviceOption> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .sortedWith(compareBy<AudioDeviceInfo> { outputPriority(it) }.thenBy { it.productName.toString() })
        return listOf(AudioDeviceOption(null, "Автоматически (система)")) + devices.map { option(it) }
    }

    fun beginRoute(input: AudioDeviceInfo?, output: AudioDeviceInfo?) {
        val communication = isBluetoothMic(input)
        audioManager.mode = if (communication) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL

        if (communication && output != null) {
            val available = audioManager.availableCommunicationDevices
            val matching = available.firstOrNull { it.id == output.id }
                ?: available.firstOrNull {
                    isBluetoothOutput(it) && isBluetoothOutput(output)
                }
            if (matching != null) {
                runCatching { audioManager.setCommunicationDevice(matching) }
            }
        }
    }

    fun clearRoute() {
        runCatching { audioManager.clearCommunicationDevice() }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun isBluetoothMic(device: AudioDeviceInfo?): Boolean = when (device?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> true
        else -> false
    }

    fun isBluetoothOutput(device: AudioDeviceInfo?): Boolean = when (device?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
        else -> false
    }

    fun isBuiltInMic(device: AudioDeviceInfo?): Boolean = when (device?.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_BACK_MIC -> true
        else -> false
    }

    fun technicalSummary(input: AudioDeviceOption?, output: AudioDeviceOption?): String {
        val inText = input?.label ?: "—"
        val outText = output?.label ?: "—"
        val mode = when {
            isBluetoothMic(input?.device) -> "Bluetooth-микрофон + коммуникационный аудиотракт"
            isBluetoothOutput(output?.device) -> "Микрофон телефона/выбранный вход → Bluetooth"
            else -> "Стандартный аудиотракт Android"
        }
        return "Вход: $inText\nВыход: $outText\nРежим: $mode\n\n" +
            "Важно: в v1.0 значение DAF — это дополнительная задержка приложения. " +
            "Собственная задержка Bluetooth пока не вычитается автоматически."
    }

    private fun option(device: AudioDeviceInfo): AudioDeviceOption {
        val product = device.productName?.toString()?.trim().orEmpty()
        val name = if (product.isBlank()) typeName(device.type) else product
        return AudioDeviceOption(device, "$name • ${typeName(device.type)}")
    }

    private fun inputPriority(device: AudioDeviceInfo): Int = when {
        isBuiltInMic(device) -> 0
        isBluetoothMic(device) -> 1
        else -> 2
    }

    private fun outputPriority(device: AudioDeviceInfo): Int = when {
        isBluetoothOutput(device) -> 0
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> 1
        else -> 2
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "микрофон телефона"
        AudioDeviceInfo.TYPE_BACK_MIC -> "задний микрофон"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "динамик телефона"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Audio"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "проводная гарнитура"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "проводные наушники"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C гарнитура"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        else -> "audio type $type"
    }
}
