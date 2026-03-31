package com.david.sundaydrive.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a user in the application.
 */
@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val userId: Long = 0,
    val username: String,
    val passwordHash: String,
    val salt: String
)
