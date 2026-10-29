package com.example.pcaudio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val svc = Intent(this, AudioService::class.java)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply {
            text = "Služba běží na pozadí a čeká, až PC požádá o přehrávání. " +
                "Povolení potvrdíš v notifikaci."
            textSize = 16f
        })
        layout.addView(Button(this).apply {
            text = "Zapnout"
            setOnClickListener { startForegroundService(svc) }
        })
        layout.addView(Button(this).apply {
            text = "Vypnout"
            setOnClickListener { stopService(svc) }
        })
        setContentView(layout)
    }
}
