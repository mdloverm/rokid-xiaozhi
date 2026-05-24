package com.rokid.xiaozhi.core

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object DeviceManager {

    private const val PREFS_NAME = "device_prefs"
    private const val KEY_MAC = "device_mac"
    private const val KEY_UUID = "device_uuid"

    private val hexChars = "0123456789abcdef".toCharArray()

    fun getMac(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var mac = prefs.getString(KEY_MAC, null)
        if (mac == null) {
            mac = generateMac()
            prefs.edit().putString(KEY_MAC, mac).apply()
        }
        return mac
    }

    fun getUuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var uuid = prefs.getString(KEY_UUID, null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UUID, uuid).apply()
        }
        return uuid
    }

    private fun generateMac(): String {
        val bytes = ByteArray(6)
        bytes[0] = 0xfe.toByte()
        val random = java.security.SecureRandom()
        random.nextBytes(bytes)
        bytes[0] = (bytes[0].toInt() and 0xfe or 0x02).toByte()
        val parts = bytes.map { "%02x".format(it) }
        return parts.joinToString(":")
    }
}
