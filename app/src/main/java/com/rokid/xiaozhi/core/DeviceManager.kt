package com.rokid.xiaozhi.core

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

object DeviceManager {

    private const val TAG = "DeviceManager"
    private const val PREFS_NAME = "device_prefs"
    private const val KEY_MAC = "device_mac"
    private const val KEY_UUID = "device_uuid"

    fun getMac(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_MAC, null)
        if (cached != null) return cached

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            val recovered = generateMacFromSeed(androidId)
            prefs.edit().putString(KEY_MAC, recovered).apply()
            Log.d(TAG, "getMac: 从 Android ID 恢复 -> $recovered")
            return recovered
        }

        val mac = generateRandomMac()
        prefs.edit().putString(KEY_MAC, mac).apply()
        Log.d(TAG, "getMac: 随机生成 -> $mac")
        return mac
    }

    fun getUuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var uuid = prefs.getString(KEY_UUID, null)
        if (uuid != null) return uuid

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            uuid = UUID.nameUUIDFromBytes(androidId.toByteArray()).toString()
            prefs.edit().putString(KEY_UUID, uuid).apply()
            Log.d(TAG, "getUuid: 从 Android ID 恢复 -> $uuid")
            return uuid
        }

        uuid = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_UUID, uuid).apply()
        return uuid
    }

    private fun generateMacFromSeed(seed: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(seed.toByteArray())
        digest[0] = (digest[0].toInt() and 0xfe or 0x02).toByte()
        val parts = digest.take(6).map { "%02x".format(it) }
        return parts.joinToString(":")
    }

    private fun generateRandomMac(): String {
        val bytes = ByteArray(6)
        bytes[0] = 0xfe.toByte()
        val random = java.security.SecureRandom()
        random.nextBytes(bytes)
        bytes[0] = (bytes[0].toInt() and 0xfe or 0x02).toByte()
        val parts = bytes.map { "%02x".format(it) }
        return parts.joinToString(":")
    }
}
