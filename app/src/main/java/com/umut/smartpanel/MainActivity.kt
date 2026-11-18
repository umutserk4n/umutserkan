package com.umut.smartpanel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView?.settings?.javaScriptEnabled = true
        
        startBlescanner()
    }

    @SuppressLint("MissingPermission")
    fun startBlescanner() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                val device = result.device
                val record = result.scanRecord
                if (device.address == "A4:C1:38:74:90:BD") {
                    val manufacturerData = record?.getManufacturerSpecificData(16877)
                    if (manufacturerData != null && manufacturerData.size >= 15) {
                        val tempRaw = ((manufacturerData[6].toInt() and 0xFF) shl 8) or (manufacturerData[7].toInt() and 0xFF)
                        val temp = tempRaw / 10.0
                        val humidity = manufacturerData[8].toInt() and 0xFF

                        runOnUiThread {
                            webView?.evaluateJavascript("window.onThermoUpdate('$temp', '$humidity')", null)
                        }
                    }
                }
            }
        }

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (_: Throwable) {}
    }
}
