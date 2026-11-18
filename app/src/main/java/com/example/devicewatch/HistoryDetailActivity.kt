package com.example.devicewatch

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

// Superseded by ParkingRecordDetailActivity; safe to delete.
class HistoryDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, ParkingRecordDetailActivity::class.java).apply {
            putExtras(intent)
        })
        finish()
    }
}
