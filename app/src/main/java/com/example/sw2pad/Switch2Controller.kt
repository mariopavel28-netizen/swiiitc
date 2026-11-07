package com.example.sw2pad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class Switch2Controller(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(msg: String)
        fun onStatus(msg: String)
        fun onState(state: Sw2.State)
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private val chars = HashMap<Int, BluetoothGattCharacteristic>()

    // Cola de operaciones GATT: Android solo admite una a la vez.
    private val queue = ArrayDeque<() -> Unit>()
    private var busy = false
    private val watchdog = Runnable {
        log("(operacion sin confirmar, continuo)")
        opDone()
    }

    @Synchronized
    private fun enqueue(op: () -> Unit) {
        queue.add(op)
        if (!busy) next()
    }

    @Synchronized
    private fun next() {
        val op = queue.poll()
        if (op == null) {
            busy = false
            return
        }
        busy = true
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 1500L)
        op()
    }

    @Synchronized
    private fun opDone() {
        main.removeCallbacks(watchdog)
        busy = false
        next()
    }

    // ---------- escaneo ----------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val md = result.scanRecord?.getManufacturerSpecificData(Sw2.MANUFACTURER_ID)
            if (md == null) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            main.post {
                log("Mando encontrado: " + result.device.address)
                log("Datos de anuncio (incluyen el product ID): " + md.hex())
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { status("Fallo de escaneo: " + errorCode) }
        }
    }

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            status("Activa el Bluetooth")
            return
        }
        status("Buscando... pon el mando en modo sincronizacion (boton SYNC)")
        a.bluetoothLeScanner.startScan(scanCb)
    }

    fun disconnect() {
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
        gatt?.close()
        gatt = null
    }

    private fun connect(dev: BluetoothDevice) {
        status("Conectando...")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    // ---------- callbacks GATT (llegan en hilos binder; todo pasa al hilo principal) ----------
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post {
                    log("Conectado, pidiendo MTU")
                    g.requestMtu(185)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post { status("Desconectado (codigo " + st + ")") }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            main.post {
                log("MTU = " + mtu)
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post { onServices(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post { opDone() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post {
                if (st != BluetoothGatt.GATT_SUCCESS) log("Escritura fallo: " + st)
                opDone()
            }
        }

        // Android 13 o superior
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val id = c.instanceId
            main.post { onNotify(id, value) }
        }

        // Android 12 o inferior
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val id = c.instanceId
            val v = c.value
            if (v == null) return
            val copy = v.copyOf()
            main.post { onNotify(id, copy) }
        }
    }

    private fun onServices(g: BluetoothGatt) {
        chars.clear()
        log("--- Volcado GATT (por si los handles no coinciden) ---")
        for (s in g.services) {
            for (c in s.characteristics) {
                log("svc=" + s.uuid.toString().take(8) + " chr=" + c.uuid +
                    " id=0x%04X props=0x%X".format(c.instanceId, c.properties))
                if (Sw2.SERVICES.contains(s.uuid)) chars[c.instanceId] = c
            }
        }
        val required = listOf(Sw2.H_INPUT, Sw2.H_COMMAND, Sw2.H_RESPONSE)
        val missing = required.filter { !chars.containsKey(it) }
        if (missing.isNotEmpty()) {
            val names = missing.joinToString(" ") { "0x%04X".format(it) }
            status("No encuentro los handles " + names + ". Mira el volcado y ajusta Protocol.kt")
            return
        }
        enableNotify(Sw2.H_INPUT)
        enableNotify(Sw2.H_RESPONSE)
        enqueue {
            startPairing()
            opDone()
        }
    }

    private fun enableNotify(handle: Int) {
        enqueue {
            val c = chars[handle]
            val g = gatt
            val d = c?.getDescriptor(CCCD)
            if (c == null || g == null || d == null) {
                log("Sin CCCD en 0x%04X".format(handle))
                opDone()
            } else {
                g.setCharacteristicNotification(c, true)
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
        }
    }

    private fun write(handle: Int, data: ByteArray) {
        enqueue {
            val c = chars[handle]
            val g = gatt
            if (c == null || g == null) {
                log("No hay caracteristica 0x%04X".format(handle))
                opDone()
            } else {
                val canNoResp = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                val canWrite = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                val type = if (canNoResp && !canWrite)
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                log("TX 0x%04X: %s".format(handle, data.hex()))
                val ok: Boolean
                if (Build.VERSION.SDK_INT >= 33) {
                    ok = g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    c.writeType = type
                    c.value = data
                    ok = g.writeCharacteristic(c)
                }
                if (!ok) {
                    log("writeCharacteristic devolvio error")
                    opDone()
                }
            }
        }
    }

    // ---------- emparejamiento de aplicacion (comando 0x15) ----------
    private var steps: List<ByteArray> = emptyList()
    private var stepIdx = 0
    private var pairing = false
    private val stepTimeout = Runnable {
        log("Sin respuesta al paso " + (stepIdx + 1) + ", sigo igualmente")
        advance()
    }

    private fun startPairing() {
        if (Sw2.keysMissing) {
            status("Faltan las claves. Escribelas en la app y vuelve a buscar el mando.")
            return
        }
        steps = Sw2.pairingSteps()
        stepIdx = 0
        pairing = true
        status("Emparejando...")
        sendStep()
    }

    private fun sendStep() {
        write(Sw2.H_COMMAND, steps[stepIdx])
        main.postDelayed(stepTimeout, 1500L)
    }

    private fun advance() {
        main.removeCallbacks(stepTimeout)
        stepIdx++
        if (stepIdx < steps.size) {
            sendStep()
        } else {
            pairing = false
            finishInit()
        }
    }

    private fun finishInit() {
        for (cmd in Sw2.featureCommands(0x2F)) write(Sw2.H_COMMAND, cmd)
        // La documentacion indica que hace falta ~500 ms tras la mascara de funciones.
        main.postDelayed({ status("Listo: mueve los sticks o pulsa botones") }, 500L)
    }

    // ---------- notificaciones ----------
    private fun onNotify(handle: Int, v: ByteArray) {
        if (handle == Sw2.H_INPUT) {
            val st = Sw2.parseInput(v)
            if (st != null) listener.onState(st)
        } else if (handle == Sw2.H_RESPONSE) {
            log("RX: " + v.hex())
            if (pairing && v.isNotEmpty() && v[0] == 0x15.toByte()) advance()
        }
    }

    private fun log(m: String) = listener.onLog(m)
    private fun status(m: String) = listener.onStatus(m)

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
