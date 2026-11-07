package com.example.sw2pad

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), Switch2Controller.Listener {

    private lateinit var controller: Switch2Controller
    private lateinit var keyEdit: EditText
    private lateinit var chalEdit: EditText
    private lateinit var statusView: TextView
    private lateinit var inputView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = Switch2Controller(this, this)
        val prefs = getSharedPreferences("sw2", MODE_PRIVATE)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        keyEdit = EditText(this)
        keyEdit.hint = "PUBLIC_KEY (16 bytes en hexadecimal)"
        keyEdit.textSize = 12f
        keyEdit.typeface = Typeface.MONOSPACE
        keyEdit.setText(prefs.getString("key", ""))

        chalEdit = EditText(this)
        chalEdit.hint = "CHALLENGE (16 bytes en hexadecimal)"
        chalEdit.textSize = 12f
        chalEdit.typeface = Typeface.MONOSPACE
        chalEdit.setText(prefs.getString("chal", ""))

        val btn = Button(this)
        btn.text = "Buscar mando Switch 2"
        btn.setOnClickListener { ensurePermsThenScan() }

        statusView = TextView(this)
        statusView.textSize = 16f
        statusView.text = "Listo para buscar"

        inputView = TextView(this)
        inputView.textSize = 18f
        inputView.typeface = Typeface.MONOSPACE

        logView = TextView(this)
        logView.textSize = 11f
        logView.typeface = Typeface.MONOSPACE
        logScroll = ScrollView(this)
        logScroll.addView(logView)

        val wide = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(keyEdit, LinearLayout.LayoutParams(wide, wrap))
        root.addView(chalEdit, LinearLayout.LayoutParams(wide, wrap))
        root.addView(btn, LinearLayout.LayoutParams(wide, wrap))
        root.addView(statusView, LinearLayout.LayoutParams(wide, wrap))
        root.addView(inputView, LinearLayout.LayoutParams(wide, wrap))
        root.addView(logScroll, LinearLayout.LayoutParams(wide, 0, 1f))
        setContentView(root)
    }

    override fun onDestroy() {
        controller.disconnect()
        super.onDestroy()
    }

    // Lee las claves escritas, las guarda y las pasa al protocolo.
    private fun applyKeys() {
        val kText = keyEdit.text.toString()
        val cText = chalEdit.text.toString()
        val k = Sw2.parseKey(kText)
        val c = Sw2.parseKey(cText)
        if (k == null || c == null) {
            Sw2.publicKey = ByteArray(16)
            Sw2.challenge = ByteArray(16)
            onLog("Claves vacias o invalidas (hacen falta 32 digitos hex cada una): solo conexion y volcado")
        } else {
            Sw2.publicKey = k
            Sw2.challenge = c
            getSharedPreferences("sw2", MODE_PRIVATE).edit()
                .putString("key", kText)
                .putString("chal", cText)
                .apply()
        }
    }

    private fun requiredPerms(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun ensurePermsThenScan() {
        applyKeys()
        val missing = requiredPerms().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            controller.startScan()
        } else {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) {
            controller.startScan()
        } else {
            onStatus("Faltan permisos de Bluetooth")
        }
    }

    // ---------- Listener ----------
    override fun onStatus(msg: String) {
        statusView.text = msg
        onLog(msg)
    }

    override fun onLog(msg: String) {
        logView.append(msg + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onState(state: Sw2.State) {
        val names = Sw2.buttonNames(state.buttons)
        val sb = StringBuilder()
        sb.append("Botones: ")
        sb.append(if (names.isEmpty()) "-" else names.joinToString(" "))
        sb.append("\n")
        sb.append("Stick L: %+.2f %+.2f\n".format(state.left.x, state.left.y))
        sb.append("Stick R: %+.2f %+.2f\n".format(state.right.x, state.right.y))
        val mv = state.batteryMv
        if (mv != null) {
            sb.append("Bateria: %d mV".format(mv))
            if (state.charging == true) sb.append(" (cargando)")
            sb.append("\n")
        }
        inputView.text = sb.toString()
    }
}
