package com.umut.smartpanel

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var webView: WebView? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var irManager: ConsumerIrManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleGatt: BluetoothGatt? = null
    private var bleWriteChar: BluetoothGattCharacteristic? = null
    private var bleScanStarted = false

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousWakeMode = false
    private var awaitingCommandAfterWake = false
    private var isSpeaking = false

    // Kalıcı ADB bağlantısı ve RSA anahtarı
    private val adbExecutor = Executors.newSingleThreadExecutor()
    private var adbSocket: Socket? = null
    private var adbConnectedIp: String = ""
    private var adbStreamCounter = 1

    private val adbKeyPair: KeyPair by lazy {
        loadOrCreateAdbKeyPair()
    }

    private fun loadOrCreateAdbKeyPair(): KeyPair {
        val prefs = getSharedPreferences("panel_cfg", Context.MODE_PRIVATE)
        val privB64 = prefs.getString("adb_priv_key", null)
        val pubB64 = prefs.getString("adb_pub_key", null)
        if (!privB64.isNullOrEmpty() && !pubB64.isNullOrEmpty()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
                return KeyPair(pub, priv)
            } catch (_: Throwable) { }
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        prefs.edit()
            .putString("adb_priv_key", Base64.getEncoder().encodeToString(kp.private.encoded))
            .putString("adb_pub_key", Base64.getEncoder().encodeToString(kp.public.encoded))
            .apply()
        return kp
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            getSharedPreferences("panel_cfg", Context.MODE_PRIVATE)
                .edit()
                .putString("last_crash", sw.toString().take(1500))
                .apply()
        }

        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("panel_cfg", Context.MODE_PRIVATE)
        val lastCrash = prefs.getString("last_crash", null)
        if (!lastCrash.isNullOrEmpty()) {
            prefs.edit().remove("last_crash").apply()
            showFallbackText("Önceki Çökme Kaydı:\n\n$lastCrash")
            return
        }

        try {
            irManager = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = btManager?.adapter
        } catch (_: Throwable) { }

        try {
            tts = TextToSpeech(this, this)
        } catch (_: Throwable) { }

        try {
            val wv = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                addJavascriptInterface(PanelBridge(), "AndroidBridge")
                loadUrl("file:///android_asset/index.html")
            }
            webView = wv
            setContentView(wv)
        } catch (t: Throwable) {
            showFallbackText("WebView Başlatılamadı:\n\n${t.message}")
            return
        }

        hideSystemUI()
        requestAllPermissions()
        if (hasBlePermission()) {
            startBleThermometerScan()
        }
    }

    private fun showFallbackText(msg: String) {
        val tv = TextView(this).apply {
            text = msg
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 14f
            setPadding(36, 36, 36, 36)
        }
        setContentView(tv)
    }

    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
            }
        } catch (_: Throwable) { }
    }

    private fun hasBlePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        try {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            val missing = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
            }
        } catch (_: Throwable) { }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && hasBlePermission() && !bleScanStarted) {
            startBleThermometerScan()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts?.language = Locale("tr", "TR")
                tts?.setSpeechRate(1.05f)
            } catch (_: Throwable) { }
        }
    }

    private fun speak(text: String, resumeWakeAfter: Boolean = true) {
        isSpeaking = true
        mainHandler.post {
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "panel_tts")
            } catch (_: Throwable) { }
            val delayMs = (text.length * 65L).coerceIn(1500L, 12000L)
            mainHandler.postDelayed({
                isSpeaking = false
                if (resumeWakeAfter && continuousWakeMode) {
                    startListeningCycle(directCommand = false)
                }
            }, delayMs)
        }
    }

    private fun jsCall(script: String) {
        mainHandler.post {
            try {
                webView?.evaluateJavascript(script, null)
            } catch (_: Throwable) { }
        }
    }

    // ==================== BLE TERMOMETRE (LYWSD03MMC) ====================
    @SuppressLint("MissingPermission")
    private fun startBleThermometerScan() {
        if (!hasBlePermission()) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            bleScanStarted = true
            scanner.startScan(null, settings, object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {val device = result.device
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
                    try {
                        val device = result.device ?: return
                        val record = result.scanRecord ?: return
                        val name = record.deviceName ?: device.name ?: ""
                        val prefs = getSharedPreferences("panel_cfg", Context.MODE_PRIVATE)
                        val targetMac = prefs.getString("thermo_mac", "")?.trim()?.uppercase() ?: ""

                        val matchesMac = targetMac.isNotEmpty() && device.address.equals(targetMac, ignoreCase = true)
                        val matchesName = name.startsWith("ATC_") || name.contains("LYWSD03MMC", ignoreCase = true)
                        if (!matchesMac && !matchesName) return

                        record.serviceData?.forEach { (uuid, data) ->
                            if (uuid.uuid.toString().startsWith("0000181a")) {
                                parseAtcOrPvvx(data)
                            }
                        }
                    } catch (_: Throwable) { }
                }
            })
        } catch (_: Throwable) {
            bleScanStarted = false
        }
    }

    private fun parseAtcOrPvvx(data: ByteArray) {
        if (data.size == 13) {
            val tempRaw = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            val temp = tempRaw.toShort() / 10.0
            val hum = data[8].toInt() and 0xFF
            jsCall("window.onThermoUpdate && window.onThermoUpdate(${String.format(Locale.US, "%.1f", temp)}, $hum)")
        } else if (data.size >= 15) {
            val tempRaw = ((data[7].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
            val humRaw = ((data[9].toInt() and 0xFF) shl 8) or (data[8].toInt() and 0xFF)
            val temp = tempRaw.toShort() / 100.0
            val hum = (humRaw and 0xFFFF) / 100
            jsCall("window.onThermoUpdate && window.onThermoUpdate(${String.format(Locale.US, "%.1f", temp)}, $hum)")
        }
    }

    // ==================== MOHUANLED (BJ_LED / ELK-BLEDOM BLE) ====================
    @SuppressLint("MissingPermission")
    private fun sendMohuanLedCommand(mac: String, payload: ByteArray) {
        if (!hasBlePermission()) return
        scope.launch {
            try {
                if (bleGatt != null && bleWriteChar != null) {
                    writeCharacteristicCompat(bleGatt!!, bleWriteChar!!, payload)
                    return@launch
                }
                val device = bluetoothAdapter?.getRemoteDevice(mac.uppercase()) ?: return@launch
                device.connectGatt(this@MainActivity, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            bleGatt = gatt
                            gatt.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            bleGatt = null
                            bleWriteChar = null
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        for (service in gatt.services) {
                            for (ch in service.characteristics) {
                                val u = ch.uuid.toString().lowercase()
                                if (u.startsWith("0000ee01") || u.startsWith("0000fff3")) {
                                    bleWriteChar = ch
                                    writeCharacteristicCompat(gatt, ch, payload)
                                    return
                                }
                            }
                        }
                    }
                })
            } catch (_: Throwable) { }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                ch.value = data
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
        } catch (_: Throwable) { }
    }

    // ==================== XIAOMI BULB 1S (YEELIGHT LAN + MIIO TOKEN) ====================
    private fun sendXiaomiBulbCommand(ip: String, tokenHex: String, method: String, paramsJson: String) {
        scope.launch {
            var yeelightOk = false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, 55443), 1200)
                    val cmd = """{"id":1,"method":"$method","params":$paramsJson}""" + "\r\n"
                    socket.getOutputStream().write(cmd.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    yeelightOk = true
                }
            } catch (_: Throwable) { }

            if (!yeelightOk && tokenHex.length == 32) {
                try {
                    sendMiioUdpCommand(ip, tokenHex, method, paramsJson)
                } catch (_: Throwable) { }
            }
        }
    }

    private fun sendMiioUdpCommand(ip: String, tokenHex: String, method: String, paramsJson: String) {
        val address = InetAddress.getByName(ip)
        DatagramSocket().use { socket ->
            socket.soTimeout = 1500
            val hello = ByteArray(32)
            hello[0] = 0x21; hello[1] = 0x31; hello[3] = 0x20
            for (i in 4 until 32) hello[i] = 0xFF.toByte()
            socket.send(DatagramPacket(hello, hello.size, address, 54321))

            val resp = ByteArray(64)
            val respPacket = DatagramPacket(resp, resp.size)
            socket.receive(respPacket)

            val deviceId = resp.copyOfRange(8, 12)
            val stamp = ByteBuffer.wrap(resp, 12, 4).int + 1
            val token = hexToBytes(tokenHex)

            val key = MessageDigest.getInstance("MD5").digest(token)
            val iv = MessageDigest.getInstance("MD5").digest(key + token)

            val payloadStr = """{"id":$stamp,"method":"$method","params":$paramsJson}"""
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(payloadStr.toByteArray(Charsets.UTF_8))

            val totalLen = 32 + encrypted.size
            val packet = ByteBuffer.allocate(totalLen)
            packet.put(0x21); packet.put(0x31)
            packet.putShort(totalLen.toShort())
            packet.putInt(0)
            packet.put(deviceId)
            packet.putInt(stamp)
            packet.put(token)
            packet.put(encrypted)

            val raw = packet.array()
            val checksum = MessageDigest.getInstance("MD5").digest(raw)
            System.arraycopy(checksum, 0, raw, 16, 16)
            socket.send(DatagramPacket(raw, raw.size, address, 54321))
        }
    }

    // ==================== KIZILÖTESİ (NEC IR) ====================
    private fun transmitNecIr(address: Int, command: Int) {
        try {
            val ir = irManager ?: return
            if (!ir.hasIrEmitter()) return
            val pattern = mutableListOf(9000, 4500)
            val bytes = intArrayOf(address and 0xFF, (address.inv()) and 0xFF, command and 0xFF, (command.inv()) and 0xFF)
            for (b in bytes) {
                for (bit in 0 until 8) {
                    pattern.add(560)
                    pattern.add(if (((b shr bit) and 1) == 1) 1690 else 560)
                }
            }
            pattern.add(560)
            ir.transmit(38000, pattern.toIntArray())
        } catch (_: Throwable) { }
    }

    // ==================== WAKE-ON-LAN (PC AÇMA) ====================
    private fun sendWakeOnLan(macStr: String) {
        scope.launch {
            try {
                val cleanMac = macStr.replace(":", "").replace("-", "").trim()
                if (cleanMac.length != 12) return@launch
                val macBytes = hexToBytes(cleanMac)
                val packetBytes = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
                for (i in 1..16) System.arraycopy(macBytes, 0, packetBytes, i * 6, 6)

                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    socket.send(DatagramPacket(packetBytes, packetBytes.size, broadcastAddr, 9))
                    socket.send(DatagramPacket(packetBytes, packetBytes.size, broadcastAddr, 7))
                }
            } catch (_: Throwable) { }
        }
    }

    // ==================== FIRE TV STICK (KALICI ADB OVER TCP) ====================
    private fun signAdbToken(token: ByteArray): ByteArray {
        val sha1Prefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val digestInfo = sha1Prefix + token
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(adbKeyPair.private)
        sig.update(digestInfo)
        return sig.sign()
    }

    private fun ensureAdbConnected(ip: String): Socket {
        val existing = adbSocket
        if (existing != null && existing.isConnected && !existing.isClosed && adbConnectedIp == ip) {
            return existing
        }
        try { existing?.close() } catch (_: Throwable) { }

        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(ip, 5555), 2500)
        socket.soTimeout = 15000

        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        writeAdbPacket(out, 0x4e584e43, 0x01000000, 4096, "host::\u0000".toByteArray())
        var msg = readAdbPacket(inp)

        if (msg.cmd == 0x48545541) { // A_AUTH (TOKEN)
            val signedToken = signAdbToken(msg.payload)
            writeAdbPacket(out, 0x48545541, 2, 0, signedToken) // ADB_AUTH_SIGNATURE
            msg = readAdbPacket(inp)

            if (msg.cmd == 0x48545541) { // Henüz onaylanmamışsa açık anahtarı gönder
                val pubKeyB64 = Base64.getEncoder().encodeToString(encodeAndroidRsaPubKey(adbKeyPair.public as RSAPublicKey))
                val pubPayload = "$pubKeyB64 smartpanel@mi6\u0000".toByteArray()
                writeAdbPacket(out, 0x48545541, 3, 0, pubPayload) // ADB_AUTH_RSAPUBLICKEY
                msg = readAdbPacket(inp)
            }
        }

        if (msg.cmd != 0x4e584e43) {
            socket.close()
            throw RuntimeException("ADB auth failed")
        }

        adbSocket = socket
        adbConnectedIp = ip
        adbStreamCounter = 1
        return socket
    }

    private fun executeAdbShellOnSocket(socket: Socket, shellCmd: String) {
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()
        val localId = adbStreamCounter++
        val dest = "shell:$shellCmd\u0000".toByteArray(Charsets.UTF_8)

        socket.soTimeout = 2000
        writeAdbPacket(out, 0x4e45504f, localId, 0, dest) // A_OPEN

        while (true) {
            val resp = readAdbPacket(inp)
            when (resp.cmd) {
                0x59414b4f -> continue // A_OKAY
                0x45545257 -> writeAdbPacket(out, 0x59414b4f, localId, resp.arg0, ByteArray(0)) // A_WRTE -> A_OKAY
                0x45534c43 -> { // A_CLSE
                    writeAdbPacket(out, 0x45534c43, localId, resp.arg0, ByteArray(0))
                    break
                }
                else -> break
            }
        }
    }

    private fun sendFireTvShell(ip: String, shellCmd: String) {
        adbExecutor.execute {
            try {
                val sock = ensureAdbConnected(ip)
                executeAdbShellOnSocket(sock, shellCmd)
            } catch (_: Throwable) {
                try { adbSocket?.close() } catch (_: Throwable) { }
                adbSocket = null
                try {
                    val sock = ensureAdbConnected(ip)
                    executeAdbShellOnSocket(sock, shellCmd)
                } catch (_: Throwable) { }
            }
        }
    }

    private data class AdbMsg(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun writeAdbPacket(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        var checksum = 0
        for (b in data) checksum += (b.toInt() and 0xFF)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(cmd)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(checksum)
        header.putInt(cmd.inv())
        out.write(header.array())
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }

    private fun readAdbPacket(inp: InputStream): AdbMsg {
        val hdr = ByteArray(24)
        var read = 0
        while (read < 24) {
            val r = inp.read(hdr, read, 24 - read)
            if (r < 0) throw RuntimeException("ADB EOF")
            read += r
        }
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        val payload = ByteArray(len)
        var pRead = 0
        while (pRead < len) {
            val r = inp.read(payload, pRead, len - pRead)
            if (r < 0) break
            pRead += r
        }
        return AdbMsg(cmd, arg0, arg1, payload)
    }

    private fun encodeAndroidRsaPubKey(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        val r = java.math.BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(n)
        val n0inv = n.negate().modInverse(java.math.BigInteger.ONE.shiftLeft(32)).toInt()
        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64)
        buf.putInt(n0inv)
        val nBytes = n.toByteArray().reversedArray()
        for (i in 0 until 256) buf.put(if (i < nBytes.size) nBytes[i] else 0)
        val rrBytes = rr.toByteArray().reversedArray()
        for (i in 0 until 256) buf.put(if (i < rrBytes.size) rrBytes[i] else 0)
        buf.putInt(pub.publicExponent.toInt())
        return buf.array()
    }

    // ==================== SESLİ ASİSTAN (JARVIS / HEY GEMINI) ====================
    private fun startListeningCycle(directCommand: Boolean) {
        if (isSpeaking) return
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                awaitingCommandAfterWake = directCommand
                jsCall("window.onVoiceState && window.onVoiceState('${if (directCommand) "listening" else "standby"}')")

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!awaitingCommandAfterWake && continuousWakeMode) {
                            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                            val heard = list.joinToString(" ").lowercase(Locale("tr", "TR"))
                            val prefs = getSharedPreferences("panel_cfg", Context.MODE_PRIVATE)
                            val wakeWord = (prefs.getString("wake_word", "jarvis") ?: "jarvis").lowercase(Locale("tr", "TR"))
                            if (heard.contains(wakeWord) || heard.contains("gemini") || heard.contains("carvis")) {
                                speechRecognizer?.cancel()
                                try {
                                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                                } catch (_: Throwable) { }
                                mainHandler.postDelayed({ startListeningCycle(directCommand = true) }, 250)
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = list?.firstOrNull()?.trim() ?: ""
                        if (spoken.isEmpty()) {
                            if (continuousWakeMode) startListeningCycle(directCommand = false)
                            else jsCall("window.onVoiceState && window.onVoiceState('idle')")
                            return
                        }

                        val prefs = getSharedPreferences("panel_cfg", Context.MODE_PRIVATE)
                        val wakeWord = (prefs.getString("wake_word", "jarvis") ?: "jarvis").lowercase(Locale("tr", "TR"))
                        val lower = spoken.lowercase(Locale("tr", "TR"))

                        if (awaitingCommandAfterWake) {
                            val cleaned = spoken.replace(Regex("(?i)^($wakeWord|carvis|hey gemini|gemini)[,\\s]*"), "").trim()
                            handleVoiceQuery(cleaned.ifEmpty { spoken })
                        } else if (lower.contains(wakeWord) || lower.contains("gemini") || lower.contains("carvis")) {
                            val cleaned = spoken.replace(Regex("(?i).*?($wakeWord|carvis|hey gemini|gemini)[,\\s]*"), "").trim()
                            if (cleaned.length > 2) {
                                handleVoiceQuery(cleaned)
                            } else {
                                try {
                                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                                } catch (_: Throwable) { }
                                startListeningCycle(directCommand = true)
                            }
                        } else if (continuousWakeMode) {
                            startListeningCycle(directCommand = false)
                        }
                    }

                    override fun onError(error: Int) {
                        if (continuousWakeMode && !isSpeaking) {
                            mainHandler.postDelayed({ startListeningCycle(directCommand = false) }, 400)
                        } else {
                            jsCall("window.onVoiceState && window.onVoiceState('idle')")
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                speechRecognizer?.startListening(intent)
            } catch (_: Throwable) {
                jsCall("window.onVoiceState && window.onVoiceState('idle')")
            }
        }
    }

    private fun handleVoiceQuery(query: String) {
        jsCall("window.onVoiceText && window.onVoiceText(${JSONObject.quote(query)}, 'user')")
        jsCall("window.onVoiceState && window.onVoiceState('thinking')")

        val q = query.lowercase(Locale("tr", "TR"))
        when {
            q.contains("ışıkları aç") || q.contains("tümünü aç") || q.contains("aydınlat") -> {
                jsCall("window.triggerScene && window.triggerScene('bright')")
                val reply = "Tüm ışıklar açıldı."
                jsCall("window.onVoiceText && window.onVoiceText(${JSONObject.quote(reply)}, 'ai')")
                speak(reply)
                return
            }
            q.contains("ışıkları kapat") || q.contains("tümünü kapat") || q.contains("her şeyi kapat") -> {
                jsCall("window.triggerScene && window.triggerScene('off')")
                val reply = "Tüm ışıklar kapatıldı."
                jsCall("window.onVoiceText && window.onVoiceText(${JSONObject.quote(reply)}, 'ai')")
                speak(reply)
                return
            }
            q.contains("sinema") -> {
                jsCall("window.triggerScene && window.triggerScene('cinema')")
                val reply = "Sinema modu aktif edildi."
                jsCall("window.onVoiceText && window.onVoiceText(${JSONObject.quote(reply)}, 'ai')")
                speak(reply)
                return
            }
            q.contains("bilgisayarı aç") || q.contains("pc aç") || q.contains("bilgisayar aç") -> {
                jsCall("window.wakePc && window.wakePc()")
                val reply = "Bilgisayara açma sinyali gönderildi."
                jsCall("window.onVoiceText && window.onVoiceText(${JSONObject.quote(reply)}, 'ai')")
                speak(reply)
                return
            }
        }

        val prefs = getSharedPreferences("panel_cfg", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_key", "")?.trim() ?: ""
        if (apiKey.isEmpty()) {
            val reply = "Sesli sorular için Ayarlar menüsünden ücretsiz Gemini API anahtarını eklemelisin."
            jsCall("window.onVoiceText && window.onVoiceText(${JSONObject.quote(reply)}, 'ai')")
            speak(reply)
            return
        }

        scope.launch {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 15000
                }

                val body = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text",
                            "Sen bir akıllı oda paneli sesli asistanısın. Kısa, net, doğal ve sesli okunmaya uygun Türkçe cevaplar ver. Maksimum 2-3 cümle kullan, markdown sembolü kullanma."
                        )))
                    })
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", query)))
                    }))
                }

                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val respStr = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(respStr)
                val answer = json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .replace("*", "")
                    .trim()

                jsCall("window.onVoiceText && window.onVoiceText(${JSONObject.quote(answer)}, 'ai')")
                speak(answer)
            } catch (_: Throwable) {
                val err = "Şu an bağlantıda bir sorun oluştu."
                jsCall("window.onVoiceText && window.onVoiceText(${JSONObject.quote(err)}, 'ai')")
                speak(err)
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    // ==================== JAVASCRIPT KÖPRÜSÜ ====================
    inner class PanelBridge {
        @JavascriptInterface
        fun saveConfig(jsonStr: String) {
            try {
                val obj = JSONObject(jsonStr)
                val prefs = getSharedPreferences("panel_cfg", Context.MODE_PRIVATE).edit()
                obj.keys().forEach { k -> prefs.putString(k, obj.optString(k)) }
                prefs.apply()
            } catch (_: Throwable) { }
        }

        @JavascriptInterface
        fun loadConfig(): String {
            return try {
                val prefs = getSharedPreferences("panel_cfg", Context.MODE_PRIVATE)
                val obj = JSONObject()
                prefs.all.forEach { (k, v) -> obj.put(k, v?.toString() ?: "") }
                obj.toString()
            } catch (_: Throwable) {
                "{}"
            }
        }

        @JavascriptInterface
        fun setBrightness(level: Float) {
            mainHandler.post {
                try {
                    val lp = window.attributes
                    lp.screenBrightness = level.coerceIn(0.01f, 1.0f)
                    window.attributes = lp
                } catch (_: Throwable) { }
            }
        }

        @JavascriptInterface
        fun controlXiaomiBulb(ip: String, token: String, method: String, paramsJson: String) {
            if (ip.isNotBlank()) sendXiaomiBulbCommand(ip.trim(), token.trim(), method, paramsJson)
        }

        @JavascriptInterface
        fun controlMohuanLed(mac: String, hexBytes: String) {
            if (mac.isNotBlank()) sendMohuanLedCommand(mac.trim(), hexToBytes(hexBytes))
        }

        @JavascriptInterface
        fun sendIr(address: Int, command: Int) {
            transmitNecIr(address, command)
        }

        @JavascriptInterface
        fun fireTvKey(ip: String, keyCode: Int) {
            if (ip.isNotBlank()) sendFireTvShell(ip.trim(), "input keyevent $keyCode")
        }

        @JavascriptInterface
        fun fireTvApp(ip: String, packageName: String) {
            if (ip.isNotBlank()) sendFireTvShell(ip.trim(), "monkey -p $packageName 1")
        }

        @JavascriptInterface
        fun wakePc(mac: String) {
            if (mac.isNotBlank()) sendWakeOnLan(mac.trim())
        }

        @JavascriptInterface
        fun triggerVoiceNow() {
            startListeningCycle(directCommand = true)
        }

        @JavascriptInterface
        fun toggleAlwaysListen(enabled: Boolean) {
            continuousWakeMode = enabled
            if (enabled) startListeningCycle(directCommand = false)
            else mainHandler.post {
                try { speechRecognizer?.cancel() } catch (_: Throwable) { }
                jsCall("window.onVoiceState && window.onVoiceState('idle')")
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        adbExecutor.shutdownNow()
        try { adbSocket?.close() } catch (_: Throwable) { }
        try { tts?.shutdown() } catch (_: Throwable) { }
        try { speechRecognizer?.destroy() } catch (_: Throwable) { }
    }
}
