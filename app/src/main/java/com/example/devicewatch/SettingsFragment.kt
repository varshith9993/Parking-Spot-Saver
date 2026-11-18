package com.example.devicewatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.devicewatch.databinding.FragmentSettingsBinding
import com.example.devicewatch.navigation.NavigationManager
import com.example.devicewatch.sensor.ShakeDetector
import com.example.devicewatch.storage.ParkingPhotoManager
import com.example.devicewatch.storage.ParkingStorage
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var parkingStorage: ParkingStorage
    private lateinit var photoManager: ParkingPhotoManager

    private var askedLocationInSession = false
    private var askedCameraInSession = false
    private var isUpdatingControls = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askedLocationInSession = true
        refreshState()
        (activity as? MainActivity)?.notifyParkingDataChanged()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        askedCameraInSession = true
        refreshState()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        parkingStorage = ParkingStorage(requireContext())
        photoManager = ParkingPhotoManager(requireContext())

        applyTopInsets()
        setupListeners()
        refreshState()
    }

    private fun applyTopInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            binding.settingsHeaderBar.setPadding(20, 14 + bars.top, 20, 14)
            insets
        }
    }

    private fun setupListeners() {
        binding.btnPermAppSettings.setOnClickListener { openAppSettings() }
        binding.btnOpenDeviceLocationSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }

        binding.toggleThemeMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingControls || !isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.btnThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (parkingStorage.getThemeMode() != mode) {
                parkingStorage.setThemeMode(mode)
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }

        binding.toggleNavMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingControls || !isChecked) return@addOnButtonCheckedListener
            val nextMode = if (checkedId == R.id.btnNavDriving) {
                NavigationManager.TRAVEL_DRIVING
            } else {
                NavigationManager.TRAVEL_WALKING
            }
            parkingStorage.setNavigationTravelMode(nextMode)
        }

        binding.switchSettingsShake.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingControls) return@setOnCheckedChangeListener
            parkingStorage.setShakeToSaveEnabled(isChecked)
            binding.layoutSettingsShakeSensitivity.visibility = if (isChecked) View.VISIBLE else View.GONE
            (activity as? MainActivity)?.updateShakeConfigFromSettings()
        }

        binding.toggleSettingsSensitivity.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingControls || !isChecked) return@addOnButtonCheckedListener
            val next = if (checkedId == R.id.btnSettingsSensitivityHigh) {
                ShakeDetector.SENSITIVITY_HIGH
            } else {
                ShakeDetector.SENSITIVITY_NORMAL
            }
            parkingStorage.setShakeSensitivity(next)
            (activity as? MainActivity)?.updateShakeConfigFromSettings()
        }

        binding.btnSettingsClearCurrent.setOnClickListener { confirmClearCurrentSpot() }
        binding.btnSettingsClearHistory.setOnClickListener { confirmClearHistory() }
        binding.btnSettingsDeleteAll.setOnClickListener { confirmDeleteAll() }
    }

    fun refreshState() {
        if (!isAdded || _binding == null) return
        updatePermissionRows()
        updateControlsFromPrefs()
        updateDataManagementButtons()
    }

    private fun updatePermissionRows() {
        val fineGranted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseGranted = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val cameraGranted = isGranted(Manifest.permission.CAMERA)

        bindPermissionRow(
            statusView = binding.tvPermPreciseStatus,
            actionButton = binding.btnPermPreciseAction,
            granted = fineGranted,
            askedInSession = askedLocationInSession,
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            onRequest = {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        )

        bindPermissionRow(
            statusView = binding.tvPermApproxStatus,
            actionButton = binding.btnPermApproxAction,
            granted = coarseGranted,
            askedInSession = askedLocationInSession,
            permission = Manifest.permission.ACCESS_COARSE_LOCATION,
            onRequest = {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        )

        bindPermissionRow(
            statusView = binding.tvPermCameraStatus,
            actionButton = binding.btnPermCameraAction,
            granted = cameraGranted,
            askedInSession = askedCameraInSession,
            permission = Manifest.permission.CAMERA,
            onRequest = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
        )
    }

    private fun bindPermissionRow(
        statusView: TextView,
        actionButton: MaterialButton,
        granted: Boolean,
        askedInSession: Boolean,
        permission: String,
        onRequest: () -> Unit
    ) {
        val ctx = requireContext()
        if (granted) {
            statusView.setText(R.string.status_granted)
            statusView.setBackgroundColor(ContextCompat.getColor(ctx, R.color.ps_granted_bg))
            statusView.setTextColor(ContextCompat.getColor(ctx, R.color.ps_granted_text))
            actionButton.setText(R.string.action_app_settings)
            actionButton.setOnClickListener { openAppSettings() }
        } else {
            statusView.setText(R.string.status_not_granted)
            statusView.setBackgroundColor(ContextCompat.getColor(ctx, R.color.ps_denied_bg))
            statusView.setTextColor(ContextCompat.getColor(ctx, R.color.ps_denied_text))

            val permanentlyDenied = askedInSession && !shouldShowRequestPermissionRationale(permission)
            if (permanentlyDenied) {
                actionButton.setText(R.string.action_app_settings)
                actionButton.setOnClickListener { openAppSettings() }
            } else {
                actionButton.setText(R.string.action_request)
                actionButton.setOnClickListener { onRequest() }
            }
        }
    }

    private fun updateControlsFromPrefs() {
        isUpdatingControls = true
        try {
            val themeButtonId = when (parkingStorage.getThemeMode()) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.btnThemeLight
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.btnThemeDark
                else -> R.id.btnThemeSystem
            }
            binding.toggleThemeMode.check(themeButtonId)

            val navButtonId = if (parkingStorage.getNavigationTravelMode() == NavigationManager.TRAVEL_DRIVING) {
                R.id.btnNavDriving
            } else {
                R.id.btnNavWalking
            }
            binding.toggleNavMode.check(navButtonId)

            val detector = ShakeDetector(requireContext()) {}
            if (!detector.isAccelerometerAvailable()) {
                binding.switchSettingsShake.isChecked = false
                binding.switchSettingsShake.isEnabled = false
                binding.layoutSettingsShakeSensitivity.visibility = View.GONE
                binding.tvSettingsShakeDesc.setText(R.string.shake_unavailable)
            } else {
                val enabled = parkingStorage.isShakeToSaveEnabled()
                binding.switchSettingsShake.isEnabled = true
                binding.switchSettingsShake.isChecked = enabled
                binding.layoutSettingsShakeSensitivity.visibility = if (enabled) View.VISIBLE else View.GONE

                val sensitivityId = if (parkingStorage.getShakeSensitivity() == ShakeDetector.SENSITIVITY_HIGH) {
                    R.id.btnSettingsSensitivityHigh
                } else {
                    R.id.btnSettingsSensitivityNormal
                }
                binding.toggleSettingsSensitivity.check(sensitivityId)
            }
        } finally {
            isUpdatingControls = false
        }
    }

    private fun updateDataManagementButtons() {
        val hasActive = parkingStorage.hasSavedParkingSpot()
        val hasHistory = parkingStorage.getHistoryRecordsCount() > 0
        val hasAny = hasActive || hasHistory || parkingStorage.getSavedRecordsCount() > 0

        binding.btnSettingsClearCurrent.isEnabled = hasActive
        binding.btnSettingsClearHistory.isEnabled = hasHistory
        binding.btnSettingsDeleteAll.isEnabled = hasAny
    }

    private fun confirmClearCurrentSpot() {
        if (!parkingStorage.hasSavedParkingSpot()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_current_confirm_title)
            .setMessage(R.string.clear_current_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_confirm_action) { _, _ ->
                parkingStorage.clearActiveSpot()
                refreshState()
                (activity as? MainActivity)?.notifyParkingDataChanged()
                Toast.makeText(requireContext(), R.string.current_spot_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun confirmClearHistory() {
        if (parkingStorage.getHistoryRecordsCount() == 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_history_confirm_title)
            .setMessage(R.string.clear_history_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_confirm_action) { _, _ ->
                val removedPhotos = parkingStorage.clearParkingHistory()
                for (path in removedPhotos) {
                    if (!parkingStorage.isPhotoReferenced(path)) {
                        photoManager.deletePhotoFile(path)
                    }
                }
                refreshState()
                (activity as? MainActivity)?.notifyParkingDataChanged()
                Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun confirmDeleteAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_all_confirm_title)
            .setMessage(R.string.delete_all_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_all_confirm_action) { _, _ ->
                parkingStorage.clearAllParkingData()
                photoManager.deleteAllParkingPhotos()
                refreshState()
                (activity as? MainActivity)?.notifyParkingDataChanged()
                Toast.makeText(requireContext(), R.string.all_data_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().packageName, null)
            )
        )
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onDestroyView() {
        photoManager.shutdown()
        _binding = null
        super.onDestroyView()
    }
}
