package com.example.sw2pad

import java.util.UUID

// Protocolo BLE de los mandos de Switch 2 (Joy-Con 2, Pro Controller 2, GameCube NSO).
// Fuente: documentacion por ingenieria inversa de bit-axis-labs y BlueRetro.
object Sw2 {
    const val MANUFACTURER_ID = 0x0553

    val SERVICES: List<UUID> = listOf(
        UUID.fromString("00c5af5d-1964-4e30-8f51-1956f96bd280"),
        UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd0")
    )

    // La documentacion identifica las caracteristicas por handle, no por UUID.
    // En Android se buscan con instanceId, que normalmente coincide con el handle.
    const val H_INPUT = 0x000A
    const val H_COMMAND = 0x0014
    const val H_RESPONSE = 0x001A

    // Direccion de anfitrion falsa: el mando no la valida.
    val HOST_ADDR = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
        0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()
    )

    // Las dos claves de 16 bytes se escriben dentro de la app (pantalla principal).
    var publicKey: ByteArray = ByteArray(16)
    var challenge: ByteArray = ByteArray(16)

    val keysMissing: Boolean
        get() = publicKey.all { it == 0.toByte() } || challenge.all { it == 0.toByte() }

    // Convierte texto como "0A 1B ..." o "0x0A, 0x1B, ..." en 16 bytes. Devuelve null si no son 32 digitos hex.
    fun parseKey(text: String): ByteArray? {
        val digits = text
            .replace("0x", "")
            .replace("0X", "")
            .filter { (it in '0'..'9') || (it in 'a'..'f') || (it in 'A'..'F') }
        if (digits.length != 32) return null
        return ByteArray(16) { i -> digits.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    // Cabecera de 8 bytes: cmd, 0x91, 0x01, sub, 0x00, len, 0x00, 0x00, y despues los datos.
    fun command(cmd: Int, sub: Int, data: ByteArray): ByteArray {
        val out = ByteArray(8 + data.size)
        out[0] = cmd.toByte()
        out[1] = 0x91.toByte()
        out[2] = 0x01
        out[3] = sub.toByte()
        out[4] = 0x00
        out[5] = data.size.toByte()
        data.copyInto(out, 8)
        return out
    }

    // Los 4 pasos del emparejamiento de aplicacion (comando 0x15).
    fun pairingSteps(): List<ByteArray> {
        val addr2 = HOST_ADDR.copyOf()
        addr2[0] = (addr2[0] - 1).toByte()
        return listOf(
            command(0x15, 0x01, byteArrayOf(0x00, 0x02) + HOST_ADDR + addr2),
            command(0x15, 0x04, byteArrayOf(0x00) + publicKey),
            command(0x15, 0x02, byteArrayOf(0x00) + challenge),
            command(0x15, 0x03, byteArrayOf(0x00))
        )
    }

    // Activar funciones de entrada (cmd 0x0C). Pro Controller 2 = 0x2F.
    // AVISO: el valor 0x2F y que va a H_COMMAND estan documentados, pero el formato exacto
    // (subcomandos 0x02 y 0x04 con mascara de 4 bytes) es una suposicion sin verificar.
    // Si no llegan datos de entrada, revisar esto primero.
    fun featureCommands(mask: Int): List<ByteArray> {
        val m = byteArrayOf(mask.toByte(), 0, 0, 0)
        return listOf(
            command(0x0C, 0x02, m),
            command(0x0C, 0x04, m)
        )
    }

    // ---------- Reporte de entrada (63 bytes) ----------

    private val BUTTONS: List<Pair<Int, String>> = listOf(
        31 to "ZL", 30 to "L", 29 to "SL(L)", 28 to "SR(L)",
        27 to "Izq", 26 to "Der", 25 to "Arriba", 24 to "Abajo",
        22 to "C", 21 to "Captura", 20 to "Home", 19 to "L3", 18 to "R3",
        17 to "+", 16 to "-",
        15 to "ZR", 14 to "R", 13 to "SL(R)", 12 to "SR(R)",
        11 to "A", 10 to "B", 9 to "X", 8 to "Y"
    )

    fun buttonNames(buttons: Long): List<String> {
        val result = ArrayList<String>()
        for (b in BUTTONS) {
            if (((buttons shr b.first) and 1L) == 1L) result.add(b.second)
        }
        return result
    }

    // Sin calibracion: neutro 2048, recorrido aproximado 1610 en el Pro Controller 2.
    class Stick(val rawX: Int, val rawY: Int) {
        val x: Float
            get() = ((rawX - 2048) / 1610f).coerceIn(-1f, 1f)
        val y: Float
            get() = ((rawY - 2048) / 1610f).coerceIn(-1f, 1f)
    }

    class State(
        val buttons: Long,
        val left: Stick,
        val right: Stick,
        val batteryMv: Int?,
        val charging: Boolean?
    )

    fun parseInput(d: ByteArray): State? {
        if (d.size < 0x10) return null
        val buttons = u(d, 3).toLong() or
            (u(d, 4).toLong() shl 8) or
            (u(d, 5).toLong() shl 16) or
            (u(d, 6).toLong() shl 24)
        var batt: Int? = null
        var chg: Boolean? = null
        if (d.size > 0x21) {
            batt = u(d, 0x1F) or (u(d, 0x20) shl 8)
            chg = u(d, 0x21) > 0
        }
        return State(buttons, stick(d, 0x0A), stick(d, 0x0D), batt, chg)
    }

    private fun u(d: ByteArray, i: Int): Int = d[i].toInt() and 0xFF

    // Cada stick son dos valores de 12 bits empaquetados en 3 bytes.
    private fun stick(d: ByteArray, o: Int): Stick {
        val x = u(d, o) or ((u(d, o + 1) and 0x0F) shl 8)
        val y = (u(d, o + 1) shr 4) or (u(d, o + 2) shl 4)
        return Stick(x, y)
    }
}

fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }
