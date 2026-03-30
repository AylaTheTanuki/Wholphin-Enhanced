package com.github.damontecres.wholphin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.MovieSectionRowPreference
import com.github.damontecres.wholphin.data.model.NavDrawerPinnedItem

@Dao
interface ServerPreferencesDao {
    fun getNavDrawerPinnedItems(user: JellyfinUser): List<NavDrawerPinnedItem> = getNavDrawerPinnedItems(user.rowId)

    @Query("SELECT * from NavDrawerPinnedItem WHERE userId=:userId")
    fun getNavDrawerPinnedItems(userId: Int): List<NavDrawerPinnedItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveNavDrawerPinnedItems(vararg items: NavDrawerPinnedItem)

    @Query("SELECT * from MovieSectionRowPreference WHERE userId=:userId ORDER BY `order` ASC")
    fun getMovieSectionRowPreferences(userId: Int): List<MovieSectionRowPreference>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveMovieSectionRowPreferences(vararg items: MovieSectionRowPreference)
}
