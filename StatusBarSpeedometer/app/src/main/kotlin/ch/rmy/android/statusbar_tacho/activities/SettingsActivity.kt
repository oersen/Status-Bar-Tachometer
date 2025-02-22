package ch.rmy.android.statusbar_tacho.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.widget.ArrayAdapter
import ch.rmy.android.statusbar_tacho.R
import ch.rmy.android.statusbar_tacho.extensions.consume
import ch.rmy.android.statusbar_tacho.extensions.ownedBy
import ch.rmy.android.statusbar_tacho.extensions.setOnItemSelectedListener
import ch.rmy.android.statusbar_tacho.location.SpeedUpdate
import ch.rmy.android.statusbar_tacho.location.SpeedWatcher
import ch.rmy.android.statusbar_tacho.services.SpeedometerService
import ch.rmy.android.statusbar_tacho.units.SpeedUnit
import ch.rmy.android.statusbar_tacho.utils.Dialogs
import ch.rmy.android.statusbar_tacho.utils.Links
import ch.rmy.android.statusbar_tacho.utils.PermissionManager
import ch.rmy.android.statusbar_tacho.utils.Settings
import ch.rmy.android.statusbar_tacho.utils.SpeedFormatter
import kotlinx.android.synthetic.main.activity_settings.*

class SettingsActivity : BaseActivity() {

    override val navigateUpIcon = 0

    private val permissionManager: PermissionManager by lazy {
        PermissionManager(context)
    }
    private val speedWatcher: SpeedWatcher by lazy {
        destroyer.own(SpeedWatcher(context))
    }

    private val settings by lazy { Settings(context) }

    private var unit: SpeedUnit
        get() = settings.unit
        set(value) {
            if (value != settings.unit) {
                settings.unit = value
                restartSpeedWatchers()
                updateViews()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupUnitSelector()
        setupCheckbox()

        toggleButton.setOnCheckedChangeListener { _, isChecked -> toggleState(isChecked) }

        speedWatcher.speedUpdates
            .subscribe { speedUpdate ->
                when (speedUpdate) {
                    is SpeedUpdate.SpeedChanged -> {
                        updateSpeedViews(speedUpdate.speed)
                    }
                    is SpeedUpdate.SpeedUnavailable -> {
                        updateSpeedViews(0f)
                    }
                }
            }
            .ownedBy(destroyer)

        Dialogs.showIntroMessage(context, settings)
    }

    private fun updateSpeedViews(speed: Float) {
        val convertedSpeed = unit.convertSpeed(speed)
        speedGauge.value = convertedSpeed
        speedText.text = when {
            !toggleButton.isChecked -> IDLE_SPEED_PLACEHOLDER
            !speedWatcher.isGPSEnabled -> getString(R.string.gps_disabled)
            !speedWatcher.hasLocationPermission() -> getString(R.string.permission_missing)
            else -> SpeedFormatter.formatSpeed(context, convertedSpeed)
        }
    }

    private fun restartSpeedWatchers() {
        if (speedWatcher.enabled) {
            speedWatcher.disable()
            speedWatcher.enable()
        }

        if (SpeedometerService.isRunning(context)) {
            SpeedometerService.restart(context)
        }
    }

    private fun updateViews() {
        val isRunning = settings.isRunning
        toggleButton.isChecked = isRunning
        speedGauge.maxValue = unit.maxValue.toFloat()
        speedGauge.markCount = unit.steps + 1
        keepOnWhileScreenOffCheckbox.visibility = if (isRunning) View.GONE else View.VISIBLE
        keepScreenOn(isRunning)
    }

    private fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onStart() {
        super.onStart()
        initState()
        updateViews()
    }

    private fun initState() {
        val state = SpeedometerService.isRunning(context)
        SpeedometerService.setRunningState(context, state)
        speedWatcher.toggle(state)
    }

    override fun onStop() {
        super.onStop()
        speedWatcher.disable()
    }

    private fun toggleState(state: Boolean) {
        if (state && !permissionManager.hasLocationPermission()) {
            toggleButton.isChecked = false
            permissionManager.requestLocationPermission(this)
            return
        }

        settings.isRunning = state
        speedWatcher.toggle(state)
        SpeedometerService.setRunningState(context, state)
        updateSpeedViews(0f)
        updateViews()
    }

    private fun setupUnitSelector() {
        val unitNames = SpeedUnit.values().map { getText(it.nameRes) }
        val dataAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, unitNames)
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        unitSpinner.adapter = dataAdapter
        unitSpinner.setSelection(SpeedUnit.values().indexOf(unit))
        unitSpinner.setOnItemSelectedListener { position ->
            unit = SpeedUnit.values()[position]
        }
    }

    private fun setupCheckbox() {
        keepOnWhileScreenOffCheckbox.isChecked = settings.shouldKeepUpdatingWhileScreenIsOff
        keepOnWhileScreenOffCheckbox.setOnCheckedChangeListener { _, checked ->
            settings.shouldKeepUpdatingWhileScreenIsOff = checked
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.settings_activity_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_github -> consume {
                Links.openGithub(context)
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissionManager.wasGranted(grantResults)) {
            toggleButton.isChecked = true
        }
    }

    companion object {

        private const val IDLE_SPEED_PLACEHOLDER = "---"

    }

}
