package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {
    @Query("SELECT * FROM location_entries ORDER BY timestamp DESC")
    fun getAllLocations(): Flow<List<LocationEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(entry: LocationEntry): Long

    @Delete
    suspend fun deleteLocation(entry: LocationEntry)

    @Query("DELETE FROM location_entries")
    suspend fun clearAll()
}
