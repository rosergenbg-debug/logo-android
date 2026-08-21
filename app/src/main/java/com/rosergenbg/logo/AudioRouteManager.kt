package com.rosergenbg.logo

import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** A selectable Android audio endpoint. A null device means "let Android choose". */
data class AudioDeviceOption(
    val device: AudioDeviceInfo?,
    val label: String
) {
    override fun toString(): String = label
}

class AudioRouteManager(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /**
     * Only expose endpoints that make sense to a normal user. Android/ColorOS also exposes
     * telephony, FM, remote-submix and other internal endpoints; those are intentionally hidden.
     */
    fun inputDevices(): List<AudioDeviceOption> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { isUsefulInput(it) }
            .distinctBy { userFacingKey(it, isInput = true) }
            .sortedWith(compareBy<AudioDeviceInfo> { inputPriority(it) }.thenBy { it.productName.toString() })

        return listOf(AudioDeviceOption(null, "Автоматический выбор")) +
            devices.map { option(it, isInput = true) }
    }

    fun outputDevices(): List<AudioDeviceOption> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isUsefulOutput(it) }
            .distinctBy { userFacingKey(it, isInput = false) }
            .sortedWith(compareBy<AudioDeviceInfo> { outputPriority(it) }.thenBy { it.productName.toString() })

        return listOf(AudioDeviceOption(null, "Автоматический выбор")) +
            devices.map { option(it, isInput = false) }
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
            if (matching != null) runCatching { audioManager.setCommunicationDevice(matching) }
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

    fun isLeAudio(device: AudioDeviceInfo?): Boolean = when (device?.type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
        else -> false
    }

    fun platformLeAudioSupported(): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return@runCatching false
            when (adapter.isLeAudioSupported) {
                BluetoothStatusCodes.FEATURE_SUPPORTED -> true
                BluetoothStatusCodes.FEATURE_NOT_SUPPORTED -> false
                else -> null
            }
        }.getOrNull()
    }

    fun outputTransportName(device: AudioDeviceInfo?): String = when (device?.type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "LE Audio / LC3 (низкая задержка)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "обычный Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth-гарнитура"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "проводные наушники"
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-C аудио"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "динамик телефона"
        null -> "автоматический выбор"
        else -> "другое аудиоустройство"
    }

    fun connectionStatus(output: AudioDeviceInfo?): String = when {
        isLeAudio(output) -> "Сейчас: LE Audio / LC3 — режим низкой задержки АКТИВЕН"
        output?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> when (platformLeAudioSupported()) {
            true -> "Сейчас: обычный Bluetooth. LE Audio/LC3 телефоном не подключён"
            false -> "Сейчас: обычный Bluetooth. Телефон не предоставляет LE Audio/LC3"
            null -> "Сейчас: обычный Bluetooth. LE Audio/LC3 не используется"
        }
        else -> "Сейчас: ${outputTransportName(output)}"
    }

    fun isBuiltInMic(device: AudioDeviceInfo?): Boolean =
        device?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC

    fun technicalSummary(input: AudioDeviceOption?, output: AudioDeviceOption?): String {
        val inText = input?.label ?: "Автоматический выбор"
        val outText = output?.label ?: "Автоматический выбор"
        return "Микрофон: $inText\nНаушники: $outText\n${connectionStatus(output?.device)}\n\n" +
            "Важно: 0 мс означает только, что Лого не добавляет свою задержку. " +
            "Сама Bluetooth-связь всё равно задерживает звук. После калибровки приложение покажет эту базовую задержку отдельно."
    }

    private fun isUsefulInput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> !looksLikePhoneInternalEndpoint(device)
        else -> false
    }

    private fun isUsefulOutput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> true
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> !looksLikePhoneInternalEndpoint(device)
        else -> false
    }

    private fun looksLikePhoneInternalEndpoint(device: AudioDeviceInfo): Boolean {
        val product = device.productName?.toString()?.trim().orEmpty()
        if (product.isBlank()) return false
        val phoneNames = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT)
            .filter { it.isNotBlank() }
        return phoneNames.any { product.equals(it, ignoreCase = true) }
    }

    private fun userFacingKey(device: AudioDeviceInfo, isInput: Boolean): String {
        if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) return "phone-mic"
        if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return "phone-speaker"
        val product = device.productName?.toString()?.trim().orEmpty().lowercase()
        return "${if (isInput) "in" else "out"}:${device.type}:$product"
    }

    private fun option(device: AudioDeviceInfo, isInput: Boolean): AudioDeviceOption {
        val product = device.productName?.toString()?.trim().orEmpty()
        val deviceName = product.ifBlank { "Аудиоустройство" }
        val label = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Микрофон телефона"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Динамик телефона"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "$deviceName — обычный Bluetooth"
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> if (isInput) {
                "$deviceName — микрофон LE Audio"
            } else {
                "$deviceName — LE Audio (низкая задержка)"
            }
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> if (isInput) {
                "$deviceName — микрофон Bluetooth"
            } else {
                "$deviceName — Bluetooth-гарнитура"
            }
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> if (isInput) "Микрофон проводной гарнитуры" else "Проводная гарнитура"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Проводные наушники"
            AudioDeviceInfo.TYPE_USB_HEADSET -> if (isInput) "Микрофон USB-C гарнитуры" else "USB-C гарнитура"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-C аудио"
            else -> deviceName
        }
        return AudioDeviceOption(device, label)
    }

    private fun inputPriority(device: AudioDeviceInfo): Int = when {
        isBuiltInMic(device) -> 0
        isLeAudio(device) -> 1
        isBluetoothMic(device) -> 2
        else -> 3
    }

    private fun outputPriority(device: AudioDeviceInfo): Int = when {
        isLeAudio(device) -> 0
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 1
        isBluetoothOutput(device) -> 2
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> 3
        device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 9
        else -> 5
    }
}
