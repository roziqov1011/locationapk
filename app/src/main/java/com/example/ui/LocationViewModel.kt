package com.example.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.location.Geocoder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.LocationTrackerApp
import com.example.data.LocationEntry
import com.example.service.LocationService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as LocationTrackerApp).repository

    val locationHistory: StateFlow<List<LocationEntry>> = repository.allLocations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val isServiceRunning: StateFlow<Boolean> = LocationService.isRunning
    val lastSavedLocation: StateFlow<LocationEntry?> = LocationService.lastSavedLocation

    private val _isManualSaving = MutableStateFlow(false)
    val isManualSaving = _isManualSaving.asStateFlow()

    private val _apiUrl = MutableStateFlow(com.example.api.LocationApiClient.getApiUrl(application))
    val apiUrl = _apiUrl.asStateFlow()

    private val _deviceName = MutableStateFlow(com.example.api.LocationApiClient.getDeviceName(application))
    val deviceName = _deviceName.asStateFlow()

    private val _deviceId = MutableStateFlow(com.example.api.LocationApiClient.getDeviceId(application))
    val deviceId = _deviceId.asStateFlow()

    val lastPostStatus: StateFlow<String> = com.example.api.LocationApiClient.lastPostStatus

    fun updateApiUrl(newUrl: String) {
        _apiUrl.value = newUrl
        com.example.api.LocationApiClient.saveApiUrl(getApplication(), newUrl)
    }

    fun updateDeviceName(newName: String) {
        _deviceName.value = newName
        com.example.api.LocationApiClient.saveDeviceName(getApplication(), newName)
    }

    fun toggleService() {
        val context = getApplication<Application>().applicationContext
        if (isServiceRunning.value) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = LocationService.ACTION_STOP
            }
            context.startService(intent)
        } else {
            val intent = Intent(context, LocationService::class.java).apply {
                action = LocationService.ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e("LocationViewModel", "Failed to start foreground service: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun saveCurrentLocationNow() {
        if (_isManualSaving.value) return
        _isManualSaving.value = true
        val context = getApplication<Application>().applicationContext
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        viewModelScope.launch {
            try {
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            viewModelScope.launch {
                                val address = getAddress(location.latitude, location.longitude)
                                val entry = LocationEntry(
                                    latitude = location.latitude,
                                    longitude = location.longitude,
                                    accuracy = location.accuracy,
                                    address = address
                                )
                                repository.insertLocation(entry)
                                _isManualSaving.value = false
                                try {
                                    com.example.api.LocationApiClient.postLocation(context, entry)
                                } catch (e: Exception) {
                                    Log.e("LocationViewModel", "Error posting from manual save: ${e.message}")
                                }
                            }
                        } else {
                            _isManualSaving.value = false
                        }
                    }
                    .addOnFailureListener {
                        _isManualSaving.value = false
                    }
            } catch (e: Exception) {
                _isManualSaving.value = false
            }
        }
    }

    private suspend fun getAddress(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(getApplication(), Locale.getDefault())
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val addressParts = mutableListOf<String>()
                for (i in 0..address.maxAddressLineIndex) {
                    addressParts.add(address.getAddressLine(i))
                }
                addressParts.joinToString(", ")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun deleteLocation(entry: LocationEntry) {
        viewModelScope.launch {
            repository.deleteLocation(entry)
        }
    }

    fun clearAllLocations() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LocationViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return LocationViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
