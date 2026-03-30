package com.github.damontecres.wholphin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.damontecres.wholphin.data.model.SearchHistoryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history WHERE userId = :userId ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentSearches(
        userId: Int,
        limit: Int = 2,
    ): Flow<List<SearchHistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntry)

    @Query(
        """
        DELETE FROM search_history
        WHERE userId = :userId
          AND query NOT IN (
            SELECT query
            FROM search_history
            WHERE userId = :userId
            ORDER BY updatedAt DESC
            LIMIT :keepLimit
          )
        """,
    )
    suspend fun trimRecentSearches(
        userId: Int,
        keepLimit: Int = 2,
    )
}
