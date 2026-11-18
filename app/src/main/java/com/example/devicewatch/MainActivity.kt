package com.example.devicewatch

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.devicewatch.databinding.ActivityMainBinding
import com.example.devicewatch.sensor.ShakeDetector
import com.example.devicewatch.storage.ParkingStorage

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_SELECTED_TAB = "selected_tab_id"
        private const val TAG_HOME = "tab_home"
        private const val TAG_SAVED = "tab_saved"
        private const val TAG_HISTORY = "tab_history"
        private const val TAG_SETTINGS = "tab_settings"
        const val EXTRA_OPEN_HOME = "extra_open_home"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var parkingStorage: ParkingStorage
    private lateinit var shakeDetector: ShakeDetector

    private var selectedTabId: Int = R.id.nav_home

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyBottomNavInsets()

        parkingStorage = ParkingStorage(this)
        shakeDetector = ShakeDetector(this) { onShakeDetected() }

        selectedTabId = savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.nav_home) ?: R.id.nav_home
        if (savedInstanceState == null) {
            setupInitialFragments()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
        binding.bottomNav.selectedItemId = selectedTabId
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_HOME, false)) {
            binding.bottomNav.selectedItemId = R.id.nav_home
            (supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment)?.refreshFromStorage(focusMap = true)
        }
    }

    private fun applyBottomNavInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(navBars.left, 0, navBars.right, navBars.bottom)
            insets
        }
    }

    private fun setupInitialFragments() {
        val home = HomeFragment()
        val saved = SavedLocationsFragment()
        val history = HistoryFragment()
        val settings = SettingsFragment()

        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, settings, TAG_SETTINGS).hide(settings)
            .add(R.id.fragmentContainer, history, TAG_HISTORY).hide(history)
            .add(R.id.fragmentContainer, saved, TAG_SAVED).hide(saved)
            .add(R.id.fragmentContainer, home, TAG_HOME)
            .commitNow()
    }

    private fun switchTab(tabId: Int) {
        selectedTabId = tabId
        val targetTag = when (tabId) {
            R.id.nav_saved -> TAG_SAVED
            R.id.nav_history -> TAG_HISTORY
            R.id.nav_settings -> TAG_SETTINGS
            else -> TAG_HOME
        }

        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        listOf(TAG_HOME, TAG_SAVED, TAG_HISTORY, TAG_SETTINGS).forEach { tag ->
            val fragment = fm.findFragmentByTag(tag)
            if (fragment != null) {
                if (tag == targetTag) {
                    tx.show(fragment)
                } else {
                    tx.hide(fragment)
                }
            }
        }
        tx.commitNow()

        refreshVisibleTab(fm.findFragmentByTag(targetTag))
    }

    private fun refreshVisibleTab(fragment: Fragment?) {
        when (fragment) {
            is HomeFragment -> fragment.refreshFromStorage(focusMap = false)
            is SavedLocationsFragment -> fragment.refreshData()
            is HistoryFragment -> fragment.refreshData()
            is SettingsFragment -> fragment.refreshState()
        }
    }

    fun navigateToHomeAndFocus() {
        binding.bottomNav.selectedItemId = R.id.nav_home
        (supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment)?.refreshFromStorage(focusMap = true)
    }

    fun notifyParkingDataChanged() {
        (supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment)?.refreshFromStorage(focusMap = false)
        (supportFragmentManager.findFragmentByTag(TAG_SAVED) as? SavedLocationsFragment)?.refreshData()
        (supportFragmentManager.findFragmentByTag(TAG_HISTORY) as? HistoryFragment)?.refreshData()
    }

    fun updateShakeConfigFromSettings() {
        if (!shakeDetector.isAccelerometerAvailable()) return
        shakeDetector.setSensitivity(parkingStorage.getShakeSensitivity())
        if (parkingStorage.isShakeToSaveEnabled()) {
            shakeDetector.start()
        } else {
            shakeDetector.stop()
        }
    }

    private fun onShakeDetected() {
        if (isFinishing || isDestroyed) return
        if (selectedTabId != R.id.nav_home) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }
        (supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment)?.triggerSaveFromShake()
    }

    override fun onResume() {
        super.onResume()
        updateShakeConfigFromSettings()
    }

    override fun onPause() {
        shakeDetector.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTabId)
    }

    override fun onDestroy() {
        shakeDetector.stop()
        super.onDestroy()
    }
}
