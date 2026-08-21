package com.rosergenbg.logo

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rosergenbg.logo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var routeManager: AudioRouteManager
    private lateinit var engine: DafAudioEngine
    private lateinit var profileStore: ProfileStore

    private var inputOptions: List<AudioDeviceOption> = emptyList()
    private var outputOptions: List<AudioDeviceOption> = emptyList()
    private var profiles: List<DafProfile> = emptyList()
    private var callbackRegistered = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioOk = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bluetoothOk = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        if (audioOk && bluetoothOk) {
            registerAudioCallback()
            refreshDevices()
            setStatus("Готово", StatusKind.READY)
        } else {
            setStatus("Нужен доступ к микрофону и Bluetooth", StatusKind.ERROR)
        }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            runOnUiThread {
                if (!engine.isRunning()) refreshDevices()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            runOnUiThread {
                if (engine.isRunning()) {
                    engine.stop()
                    setRunningUi(false)
                    setStatus("Аудиоустройство отключено", StatusKind.ERROR)
                }
                refreshDevices()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        routeManager = AudioRouteManager(this)
        engine = DafAudioEngine(routeManager)
        profileStore = ProfileStore(this)

        engine.onUnexpectedStop = { message ->
            runOnUiThread {
                setRunningUi(false)
                setStatus("Ошибка: $message", StatusKind.ERROR)
            }
        }

        setupControls()
        refreshProfiles()

        if (hasPermissions()) {
            registerAudioCallback()
            refreshDevices()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (::engine.isInitialized && hasPermissions()) registerAudioCallback()
    }

    override fun onStop() {
        if (::engine.isInitialized && engine.isRunning()) {
            engine.stop()
            if (::binding.isInitialized) {
                setRunningUi(false)
                setStatus("Остановлено", StatusKind.READY)
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.stop()
        if (callbackRegistered && ::routeManager.isInitialized) {
            val audioManager = getSystemService(android.media.AudioManager::class.java)
            runCatching { audioManager.unregisterAudioDeviceCallback(audioCallback) }
            callbackRegistered = false
        }
        super.onDestroy()
    }

    private fun setupControls() {
        binding.delaySeek.progress = 15
        binding.delayValueText.text = "75 мс"
        engine.delayMs = 75

        binding.delaySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val delay = progress * 5
                binding.delayValueText.text = "$delay мс"
                engine.delayMs = delay
                if (engine.isRunning()) {
                    setStatus("Аудио активно • $delay мс", StatusKind.ACTIVE)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.volumeSeek.progress = 80
        binding.volumeValueText.text = "80%"
        engine.volume = 0.8f
        binding.volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.volumeValueText.text = "$progress%"
                engine.volume = progress / 100f
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        mapOf(
            binding.quick0 to 0,
            binding.quick50 to 50,
            binding.quick75 to 75,
            binding.quick100 to 100,
            binding.quick125 to 125,
            binding.quick150 to 150
        ).forEach { (button, value) ->
            button.setOnClickListener { binding.delaySeek.progress = value / 5 }
        }

        binding.refreshButton.setOnClickListener {
            if (hasPermissions()) refreshDevices() else requestPermissionsAgain()
        }

        binding.startButton.setOnClickListener { startDaf() }
        binding.stopButton.setOnClickListener {
            engine.stop()
            setRunningUi(false)
            setStatus("Остановлено", StatusKind.READY)
        }

        val routeListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTechnicalInfo()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.inputSpinner.onItemSelectedListener = routeListener
        binding.outputSpinner.onItemSelectedListener = routeListener

        binding.saveProfileButton.setOnClickListener { showSaveProfileDialog() }
        binding.applyProfileButton.setOnClickListener { applySelectedProfile() }
        binding.deleteProfileButton.setOnClickListener { deleteSelectedProfile() }
    }

    private fun startDaf() {
        if (!hasPermissions()) {
            requestPermissionsAgain()
            return
        }
        if (engine.isRunning()) return

        val input = binding.inputSpinner.selectedItem as? AudioDeviceOption
        val output = binding.outputSpinner.selectedItem as? AudioDeviceOption

        engine.delayMs = binding.delaySeek.progress * 5
        engine.volume = binding.volumeSeek.progress / 100f

        setStatus("Запуск аудио…", StatusKind.READY)
        val result = engine.start(input?.device, output?.device)
        result.onSuccess {
            setRunningUi(true)
            setStatus("Аудио активно • ${engine.delayMs} мс", StatusKind.ACTIVE)
        }.onFailure {
            setRunningUi(false)
            setStatus("Не удалось запустить: ${it.message}", StatusKind.ERROR)
        }
    }

    private fun refreshDevices() {
        if (!hasPermissions() || engine.isRunning()) return

        val oldInput = (binding.inputSpinner.selectedItem as? AudioDeviceOption)?.label
        val oldOutput = (binding.outputSpinner.selectedItem as? AudioDeviceOption)?.label

        runCatching {
            inputOptions = routeManager.inputDevices()
            outputOptions = routeManager.outputDevices()
        }.onFailure {
            setStatus("Не удалось прочитать аудиоустройства", StatusKind.ERROR)
            return
        }

        val inputAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, inputOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val outputAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, outputOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.inputSpinner.adapter = inputAdapter
        binding.outputSpinner.adapter = outputAdapter

        val inputIndex = oldInput?.let { label -> inputOptions.indexOfFirst { it.label == label } }
            ?.takeIf { it >= 0 }
            ?: inputOptions.indexOfFirst { routeManager.isBuiltInMic(it.device) }.takeIf { it >= 0 }
            ?: 0

        val outputIndex = oldOutput?.let { label -> outputOptions.indexOfFirst { it.label == label } }
            ?.takeIf { it >= 0 }
            ?: outputOptions.indexOfFirst { routeManager.isBluetoothOutput(it.device) }.takeIf { it >= 0 }
            ?: 0

        binding.inputSpinner.setSelection(inputIndex)
        binding.outputSpinner.setSelection(outputIndex)
        updateTechnicalInfo()
    }

    private fun updateTechnicalInfo() {
        if (!::routeManager.isInitialized || !::binding.isInitialized) return
        val input = binding.inputSpinner.selectedItem as? AudioDeviceOption
        val output = binding.outputSpinner.selectedItem as? AudioDeviceOption
        binding.technicalInfoText.text = routeManager.technicalSummary(input, output)
    }

    private fun setRunningUi(running: Boolean) {
        binding.startButton.isEnabled = !running
        binding.stopButton.isEnabled = running
        binding.inputSpinner.isEnabled = !running
        binding.outputSpinner.isEnabled = !running
        binding.refreshButton.isEnabled = !running
        binding.applyProfileButton.isEnabled = !running
        binding.deleteProfileButton.isEnabled = !running
        binding.saveProfileButton.isEnabled = !running
    }

    private fun showSaveProfileDialog() {
        val nameField = EditText(this).apply {
            hint = "Например: Sony XM6 — 75 мс"
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Сохранить профиль")
            .setView(nameField)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = nameField.text.toString().trim()
                if (name.isBlank()) {
                    toast("Введите название профиля")
                    return@setPositiveButton
                }
                val input = binding.inputSpinner.selectedItem as? AudioDeviceOption
                val output = binding.outputSpinner.selectedItem as? AudioDeviceOption
                profileStore.save(
                    DafProfile(
                        name = name,
                        inputLabel = input?.label ?: "Автоматически (система)",
                        outputLabel = output?.label ?: "Автоматически (система)",
                        delayMs = binding.delaySeek.progress * 5,
                        volumePercent = binding.volumeSeek.progress
                    )
                )
                refreshProfiles(name)
                toast("Профиль сохранён")
            }
            .show()
    }

    private fun refreshProfiles(selectName: String? = null) {
        profiles = profileStore.load()
        val names = if (profiles.isEmpty()) listOf("Нет сохранённых профилей") else profiles.map { it.name }
        binding.profileSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            names
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        if (selectName != null) {
            val index = profiles.indexOfFirst { it.name == selectName }
            if (index >= 0) binding.profileSpinner.setSelection(index)
        }
        val enabled = profiles.isNotEmpty()
        binding.applyProfileButton.isEnabled = enabled && !engine.isRunning()
        binding.deleteProfileButton.isEnabled = enabled && !engine.isRunning()
    }

    private fun applySelectedProfile() {
        if (profiles.isEmpty()) {
            toast("Сначала сохраните профиль")
            return
        }
        val index = binding.profileSpinner.selectedItemPosition.coerceIn(0, profiles.lastIndex)
        val profile = profiles[index]

        binding.delaySeek.progress = (profile.delayMs / 5).coerceIn(0, 50)
        binding.volumeSeek.progress = profile.volumePercent

        val inputIndex = inputOptions.indexOfFirst { it.label == profile.inputLabel }
        if (inputIndex >= 0) binding.inputSpinner.setSelection(inputIndex)
        val outputIndex = outputOptions.indexOfFirst { it.label == profile.outputLabel }
        if (outputIndex >= 0) binding.outputSpinner.setSelection(outputIndex)

        updateTechnicalInfo()
        toast("Профиль «${profile.name}» применён")
    }

    private fun deleteSelectedProfile() {
        if (profiles.isEmpty()) return
        val index = binding.profileSpinner.selectedItemPosition.coerceIn(0, profiles.lastIndex)
        val profile = profiles[index]

        AlertDialog.Builder(this)
            .setTitle("Удалить профиль?")
            .setMessage(profile.name)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                profileStore.delete(profile.name)
                refreshProfiles()
                toast("Профиль удалён")
            }
            .show()
    }

    private fun registerAudioCallback() {
        if (callbackRegistered || !hasPermissions()) return
        val audioManager = getSystemService(android.media.AudioManager::class.java)
        runCatching {
            audioManager.registerAudioDeviceCallback(audioCallback, null)
            callbackRegistered = true
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsAgain() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    }

    private fun setStatus(text: String, kind: StatusKind) {
        binding.statusText.text = text
        val color = when (kind) {
            StatusKind.READY -> R.color.logo_black
            StatusKind.ACTIVE -> R.color.logo_green
            StatusKind.ERROR -> R.color.logo_red
        }
        binding.statusText.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private enum class StatusKind { READY, ACTIVE, ERROR }
}
