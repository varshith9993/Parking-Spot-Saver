package com.example.devicewatch

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

// Superseded by HistoryFragment in bottom navigation; safe to delete.
class HistoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
