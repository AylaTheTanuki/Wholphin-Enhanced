package com.github.damontecres.wholphin.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "search_history",
    primaryKeys = ["userId", "query"],
    foreignKeys = [
        ForeignKey(
            entity = JellyfinUser::class,
            parentColumns = ["rowId"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("userId"),
        Index(value = ["userId", "updatedAt"]),
    ],
)
data class SearchHistoryEntry(
    val userId: Int,
    val query: String,
    val updatedAt: Long,
)
