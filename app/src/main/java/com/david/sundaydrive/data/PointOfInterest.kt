package com.david.sundaydrive.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a Point of Interest saved by a user.
 */
@Entity(
    tableName = "points_of_interest",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["userId"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["userId"])]
)
data class PointOfInterest(
    @PrimaryKey(autoGenerate = true) val poiId: Long = 0,
    val userId: Long,
    val placeName: String,
    val latitude: Double,
    val longitude: Double,
    val type: String,
    val timestamp: Long = System.currentTimeMillis()
)
