package com.example.devicewatch

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.devicewatch.storage.ParkingStorage

class ParkingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val themeMode = ParkingStorage(this).getThemeMode()
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }
}
