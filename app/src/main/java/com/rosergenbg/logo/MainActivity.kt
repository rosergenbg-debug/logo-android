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
    private lateinit var calibrationStore: CalibrationStore
    private lateinit var calibrationEngine: CalibrationEngine

    private var inputOptions: List<AudioDeviceOption> = emptyList()
    private var outputOptions: List<AudioDeviceOption> = emptyList()
    private var profiles: List<DafProfile> = emptyList()
    private var callbackRegistered = false
    private var calibrating = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioOk = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bluetoothOk = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        if (audioOk && bluetoothOk) {
            registerAudioCallback()
            refreshDevices(force = true)
            setStatus("Готово", StatusKind.READY)
        } else {
            setStatus("Нужен доступ к микрофону и Bluetooth", StatusKind.ERROR)
        }
    }

    /**
     * Android frequently removes/adds logical A2DP/SCO/BLE endpoints while changing a route.
     * v1.0 treated every such event as a physical disconnect and stopped DAF. v1.1 does not.
     * The real audio engine itself decides whether a route has actually failed.
     */
    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            runOnUiThread {
                if (!engine.isRunning() && !calibrating) refreshDevices()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            runOnUiThread {
                if (!engine.isRunning() && !calibrating) refreshDevices()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DafRuntime.initialize(applicationContext)
        routeManager = DafRuntime.routeManager
        engine = DafRuntime.engine
        profileStore = ProfileStore(this)
        calibrationStore = CalibrationStore(this)
        calibrationEngine = CalibrationEngine(routeManager)

        engine.onUnexpectedStop = { message ->
            AudioKeepAliveService.stop(applicationContext)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    setRunningUi(false)
                    setStatus("Ошибка аудио: $message", StatusKind.ERROR)
                }
            }
        }

        setupControls()
        refreshProfiles()

        if (hasPermissions()) {
            registerAudioCallback()
            refreshDevices(force = true)
        } else {
            requestPermissionsAgain()
        }

        if (engine.isRunning()) {
            binding.delaySeek.progress = engine.delayMs / 5
            binding.volumeSeek.progress = (engine.volume * 100).toInt()
            setRunningUi(true)
            setStatus("Аудио активно • добавлено ${engine.delayMs} мс", StatusKind.ACTIVE)
        }
    }

    override fun onStart() {
        super.onStart()
        if (::engine.isInitialized && hasPermissions()) registerAudioCallback()
    }

    override fun onResume() {
        super.onResume()
        if (::engine.isInitialized) {
            if (engine.isRunning()) {
                setRunningUi(true)
                setStatus("Аудио активно • добавлено ${engine.delayMs} мс", StatusKind.ACTIVE)
            } else if (!calibrating) {
                AudioKeepAliveService.stop(applicationContext)
            }
        }
    }

    /** DAF intentionally keeps running when another app covers Logo or the screen is backgrounded. */
    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        if (callbackRegistered) {
            val audioManager = getSystemService(android.media.AudioManager::class.java)
            runCatching { audioManager.unregisterAudioDeviceCallback(audioCallback) }
            callbackRegistered = false
        }
        // Do not stop the process-wide engine here. The user stops DAF explicitly with STOP.
        super.onDestroy()
    }

    private fun setupControls() {
        val initialDelay = if (engine.isRunning()) engine.delayMs else 75
        binding.delaySeek.progress = initialDelay / 5
        binding.delayValueText.text = "$initialDelay мс"
        if (!engine.isRunning()) engine.delayMs = initialDelay

        binding.delaySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val delay = progress * 5
                binding.delayValueText.text = "$delay мс"
                engine.delayMs = delay
                updateCalibrationInfo()
                if (engine.isRunning()) {
                    setStatus("Аудио активно • добавлено $delay мс", StatusKind.ACTIVE)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val initialVolume = if (engine.isRunning()) (engine.volume * 100).toInt() else 80
        binding.volumeSeek.progress = initialVolume
        binding.volumeValueText.text = "$initialVolume%"
        if (!engine.isRunning()) engine.volume = initialVolume / 100f

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
            if (hasPermissions()) refreshDevices(force = true) else requestPermissionsAgain()
        }

        binding.calibrateButton.setOnClickListener { showCalibrationDialog() }
        binding.startButton.setOnClickListener { startDaf() }
        binding.stopButton.setOnClickListener { stopDaf() }

        val routeListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTechnicalInfo()
                updateCalibrationInfo()
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
        if (engine.isRunning() || calibrating) return

        val input = binding.inputSpinner.selectedItem as? AudioDeviceOption
        val output = binding.outputSpinner.selectedItem as? AudioDeviceOption

        engine.delayMs = binding.delaySeek.progress * 5
        engine.volume = binding.volumeSeek.progress / 100f

        setStatus("Запуск аудио…", StatusKind.READY)
        engine.start(input?.device, output?.device)
            .onSuccess {
                runCatching { AudioKeepAliveService.start(applicationContext, engine.delayMs) }
                setRunningUi(true)
                setStatus("Аудио активно • добавлено ${engine.delayMs} мс", StatusKind.ACTIVE)
            }
            .onFailure {
                setRunningUi(false)
                setStatus("Не удалось запустить: ${it.message}", StatusKind.ERROR)
            }
    }

    private fun stopDaf() {
        engine.stop()
        AudioKeepAliveService.stop(applicationContext)
        setRunningUi(false)
        setStatus("Остановлено", StatusKind.READY)
        updateCalibrationInfo()
    }

    private fun showCalibrationDialog() {
        if (!hasPermissions()) {
            requestPermissionsAgain()
            return
        }
        if (calibrating) return

        AlertDialog.Builder(this)
            .setTitle("Калибровка задержки")
            .setMessage(
                "1. Выньте один наушник из уха.\n\n" +
                    "2. Приложите его звуководом/динамиком вплотную к микрофону телефона. " +
                    "Для OnePlus удобнее начать с нижнего микрофона рядом с USB-C.\n\n" +
                    "3. Держите телефон и наушник неподвижно в тихом месте.\n\n" +
                    "4. Нажмите «Начать». Прозвучат четыре коротких сигнала. " +
                    "Лого измерит полную задержку микрофон → Android → Bluetooth → наушник."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Начать") { _, _ -> runCalibration() }
            .show()
    }

    private fun runCalibration() {
        val input = binding.inputSpinner.selectedItem as? AudioDeviceOption
        val output = binding.outputSpinner.selectedItem as? AudioDeviceOption
        if (output == null) {
            toast("Сначала выберите аудиовыход")
            return
        }

        if (engine.isRunning()) stopDaf()
        calibrating = true
        setCalibratingUi(true)
        setStatus("Калибровка… держите наушник у микрофона", StatusKind.ACTIVE)
        binding.calibrationValueText.text = "Измерение…"
        binding.totalDelayText.text = "Прозвучат 4 тестовых сигнала"

        Thread({
            val result = calibrationEngine.measure(input?.device, output.device)
            runOnUiThread {
                calibrating = false
                setCalibratingUi(false)
                result.onSuccess { measured ->
                    calibrationStore.save(calibrationKey(input, output), measured.latencyMs)
                    updateCalibrationInfo()
                    setStatus("Калибровка готова • ${measured.latencyMs} мс", StatusKind.READY)
                    toast("Измерено примерно ${measured.latencyMs} мс")
                }.onFailure { error ->
                    updateCalibrationInfo()
                    setStatus("Калибровка не удалась", StatusKind.ERROR)
                    AlertDialog.Builder(this)
                        .setTitle("Не удалось измерить")
                        .setMessage(error.message ?: "Поднесите наушник ближе к микрофону и повторите тест.")
                        .setPositiveButton("ОК", null)
                        .show()
                }
            }
        }, "Logo-Calibration").start()
    }

    private fun refreshDevices(force: Boolean = false) {
        if (!hasPermissions() || calibrating) return
        if (engine.isRunning() && !force) return

        val oldInput = (binding.inputSpinner.selectedItem as? AudioDeviceOption)?.label
        val oldOutput = (binding.outputSpinner.selectedItem as? AudioDeviceOption)?.label

        runCatching {
            inputOptions = routeManager.inputDevices()
            outputOptions = routeManager.outputDevices()
        }.onFailure {
            setStatus("Не удалось прочитать аудиоустройства", StatusKind.ERROR)
            return
        }

        binding.inputSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            inputOptions
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.outputSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            outputOptions
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val inputIndex = oldInput?.let { label -> inputOptions.indexOfFirst { it.label == label } }
            ?.takeIf { it >= 0 }
            ?: inputOptions.indexOfFirst { routeManager.isBuiltInMic(it.device) }.takeIf { it >= 0 }
            ?: 0

        val outputIndex = oldOutput?.let { label -> outputOptions.indexOfFirst { it.label == label } }
            ?.takeIf { it >= 0 }
            ?: outputOptions.indexOfFirst { routeManager.isLeAudio(it.device) }.takeIf { it >= 0 }
            ?: outputOptions.indexOfFirst { routeManager.isBluetoothOutput(it.device) }.takeIf { it >= 0 }
            ?: 0

        binding.inputSpinner.setSelection(inputIndex)
        binding.outputSpinner.setSelection(outputIndex)
        updateTechnicalInfo()
        updateCalibrationInfo()
        setRunningUi(engine.isRunning())
    }

    private fun updateTechnicalInfo() {
        if (!::routeManager.isInitialized || !::binding.isInitialized) return
        val input = binding.inputSpinner.selectedItem as? AudioDeviceOption
        val output = binding.outputSpinner.selectedItem as? AudioDeviceOption
        binding.technicalInfoText.text = routeManager.technicalSummary(input, output)

        val transport = routeManager.outputTransportName(output?.device)
        binding.connectionModeText.text = when {
            routeManager.isLeAudio(output?.device) -> "Аудиотракт: $transport • LC3 активен"
            output?.device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
                "Аудиотракт: $transport • LC3 сейчас НЕ активен"
            else -> "Аудиотракт: $transport"
        }
    }

    private fun calibrationKey(input: AudioDeviceOption?, output: AudioDeviceOption?): String =
        "${input?.label ?: "auto"} -> ${output?.label ?: "auto"}"

    private fun updateCalibrationInfo() {
        if (!::binding.isInitialized || !::calibrationStore.isInitialized) return
        val input = binding.inputSpinner.selectedItem as? AudioDeviceOption
        val output = binding.outputSpinner.selectedItem as? AudioDeviceOption
        val added = binding.delaySeek.progress * 5
        val base = output?.let { calibrationStore.load(calibrationKey(input, it)) }

        if (base == null) {
            binding.calibrationValueText.text = "Базовая задержка: не измерена"
            binding.totalDelayText.text = "Итого: неизвестно + $added мс"
        } else {
            binding.calibrationValueText.text = "Базовая задержка: ≈ $base мс"
            binding.totalDelayText.text = "Итого ориентировочно: ≈ ${base + added} мс ($base + $added)"
        }
    }

    private fun setRunningUi(running: Boolean) {
        if (!::binding.isInitialized) return
        binding.startButton.isEnabled = !running && !calibrating
        binding.stopButton.isEnabled = running && !calibrating
        binding.inputSpinner.isEnabled = !running && !calibrating
        binding.outputSpinner.isEnabled = !running && !calibrating
        binding.refreshButton.isEnabled = !running && !calibrating
        binding.calibrateButton.isEnabled = !running && !calibrating
        binding.applyProfileButton.isEnabled = !running && !calibrating && profiles.isNotEmpty()
        binding.deleteProfileButton.isEnabled = !running && !calibrating && profiles.isNotEmpty()
        binding.saveProfileButton.isEnabled = !running && !calibrating
    }

    private fun setCalibratingUi(active: Boolean) {
        binding.startButton.isEnabled = !active
        binding.stopButton.isEnabled = false
        binding.inputSpinner.isEnabled = !active
        binding.outputSpinner.isEnabled = !active
        binding.refreshButton.isEnabled = !active
        binding.calibrateButton.isEnabled = !active
        binding.delaySeek.isEnabled = !active
        binding.volumeSeek.isEnabled = !active
        binding.saveProfileButton.isEnabled = !active
        binding.applyProfileButton.isEnabled = !active && profiles.isNotEmpty()
        binding.deleteProfileButton.isEnabled = !active && profiles.isNotEmpty()
    }

    private fun showSaveProfileDialog() {
        val nameField = EditText(this).apply {
            hint = "Например: Sony XM6 LE — 75 мс"
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
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        if (selectName != null) {
            val index = profiles.indexOfFirst { it.name == selectName }
            if (index >= 0) binding.profileSpinner.setSelection(index)
        }
        setRunningUi(engine.isRunning())
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
        updateCalibrationInfo()
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

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

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
