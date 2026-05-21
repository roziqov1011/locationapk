package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.LocationTrackerApp
import com.example.MainActivity
import com.example.data.LocationEntry
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocationService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val NOTIFICATION_ID = 45912

        val isRunning = MutableStateFlow(false)
        val lastSavedLocation = MutableStateFlow<LocationEntry?>(null)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "Service created")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("LocationService", "Service command received: $action")
        when (action) {
            ACTION_START -> {
                startLocationTracking()
            }
            ACTION_STOP -> {
                stopLocationTracking()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (isRunning.value) return

        isRunning.value = true
        startForegroundServiceWithNotification()

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 300000L // 5 minutes standard interval
        ).apply {
            setMinUpdateIntervalMillis(300000L) // limit max frequency
            setWaitForAccurateLocation(true)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    saveLocationToDatabase(location.latitude, location.longitude, location.accuracy)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                mainLooper
            )
            Log.d("LocationService", "Location updates requested successfully")
            
            // Also fetch current location immediately on start so the user does not have to wait 5 minutes
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    saveLocationToDatabase(it.latitude, it.longitude, it.accuracy)
                }
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Error requesting location updates: ${e.message}")
            isRunning.value = false
            stopSelf()
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notification = createNotification(".", ".")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(address: String?, lat: Double, lon: Double) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID,
            createNotification(".", ".")
        )
    }

    private fun createNotification(title: String, contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "location_tracker_channel")
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun saveLocationToDatabase(latitude: Double, longitude: Double, accuracy: Float) {
        serviceScope.launch {
            val address = getAddress(latitude, longitude)
            val entry = LocationEntry(
                latitude = latitude,
                longitude = longitude,
                accuracy = accuracy,
                address = address
            )
            
            val id = (application as LocationTrackerApp).repository.insertLocation(entry)
            val savedEntry = entry.copy(id = id)
            lastSavedLocation.value = savedEntry
            
            Log.d("LocationService", "Saved location: $savedEntry")
            updateNotification(address, latitude, longitude)

            // Dynamic API POST upload sync
            try {
                com.example.api.LocationApiClient.postLocation(applicationContext, savedEntry)
            } catch (e: Exception) {
                Log.e("LocationService", "Error posting to API: ${e.message}")
            }
        }
    }

    private suspend fun getAddress(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(applicationContext, Locale.getDefault())
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val addressParts = mutableListOf<String>()
                // getAddressLine index ranges from 0 to maxAddressLineIndex
                for (i in 0..address.maxAddressLineIndex) {
                    addressParts.add(address.getAddressLine(i))
                }
                addressParts.joinToString(", ")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Geocoding error: ${e.message}")
            null
        }
    }

    private fun stopLocationTracking() {
        if (!isRunning.value) return

        isRunning.value = false
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d("LocationService", "Service tracking stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        Log.d("LocationService", "Service destroyed")
    }
}
