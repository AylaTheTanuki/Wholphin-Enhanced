package com.github.damontecres.wholphin.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

enum class NavPinType {
    PINNED,
    UNPINNED,
}

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = JellyfinUser::class,
            parentColumns = arrayOf("rowId"),
            childColumns = arrayOf("userId"),
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["userId", "itemId"],
)
data class NavDrawerPinnedItem(
    val userId: Int,
    val itemId: String,
    val type: NavPinType,
    @ColumnInfo(defaultValue = "-1") val order: Int,
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = JellyfinUser::class,
            parentColumns = arrayOf("rowId"),
            childColumns = arrayOf("userId"),
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["userId", "rowId"],
)
data class MovieSectionRowPreference(
    val userId: Int,
    val rowId: String,
    @ColumnInfo(defaultValue = "-1") val order: Int,
)
