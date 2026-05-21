package com.example.data

import kotlinx.coroutines.flow.Flow

class LocationRepository(private val locationDao: LocationDao) {
    val allLocations: Flow<List<LocationEntry>> = locationDao.getAllLocations()

    suspend fun insertLocation(entry: LocationEntry): Long {
        return locationDao.insertLocation(entry)
    }

    suspend fun deleteLocation(entry: LocationEntry) {
        locationDao.deleteLocation(entry)
    }

    suspend fun clearAll() {
        locationDao.clearAll()
    }
}
