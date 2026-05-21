package com.example.api

import android.content.Context
import android.util.Log
import com.example.data.LocationEntry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object LocationApiClient {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _lastPostStatus = MutableStateFlow<String>("Hali jo'natilmadi")
    val lastPostStatus = _lastPostStatus.asStateFlow()

    // SharedPreferences key for the API url
    private const val PREFS_NAME = "location_tracker_prefs"
    private const val KEY_API_URL = "api_server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    
    // Default points to the custom user specified production API server
    const val DEFAULT_API_URL = "https://location-14kd.onrender.com/api/locations"

    fun getApiUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_API_URL, null)
        if (saved == null || saved == "http://10.0.2.2:3000/api/locations" || saved == "http://192.168.30.123:3000/api/locations") {
            return DEFAULT_API_URL
        }
        return saved
    }

    fun saveApiUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_URL, url).apply()
    }

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                java.util.UUID.randomUUID().toString().substring(0, 8)
            }
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getDeviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var name = prefs.getString(KEY_DEVICE_NAME, null)
        if (name.isNullOrBlank()) {
            name = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
            if (name.isBlank()) name = "Noma'lum qurilma"
            prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
        }
        return name
    }

    fun saveDeviceName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    suspend fun postLocation(context: Context, entry: LocationEntry) = withContext(Dispatchers.IO) {
        val targetUrl = getApiUrl(context)
        if (targetUrl.isBlank()) {
            _lastPostStatus.value = "Xatolik: API manzili kiritilmagan"
            return@withContext
        }

        try {
            val nameValue = entry.address ?: "Toshkent, Joylashuv"
            val deviceId = getDeviceId(context)
            val deviceName = getDeviceName(context)
            
            val payload = mapOf(
                "name" to nameValue,
                "latitude" to entry.latitude,
                "longitude" to entry.longitude,
                "device_id" to deviceId,
                "deviceId" to deviceId,
                "device_name" to deviceName,
                "deviceName" to deviceName
            )
            
            val jsonAdapter = moshi.adapter(Map::class.java)
            val jsonString = jsonAdapter.toJson(payload)

            val body = jsonString.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(targetUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build()

            _lastPostStatus.value = "Jo'natilmoqda..."
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBodyString = response.body?.string() ?: ""
                    Log.d("LocationApiClient", "Successfully posted location! Response: $responseBodyString")
                    _lastPostStatus.value = "Muvaffaqiyatli jo'natildi (HTTP ${response.code})"
                } else {
                    Log.e("LocationApiClient", "Failed to post location: HTTP ${response.code}")
                    _lastPostStatus.value = "Xatolik: Server xatosi (HTTP ${response.code})"
                }
            }
        } catch (e: Exception) {
            Log.e("LocationApiClient", "Network exception posting location: ${e.message}")
            _lastPostStatus.value = "Ulanish xatosi: ${e.localizedMessage ?: "Noma'lum xatolik"}"
        }
    }
}
