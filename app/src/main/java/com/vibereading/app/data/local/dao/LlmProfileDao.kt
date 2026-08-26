package com.vibereading.app.data.local.dao

import androidx.room.*
import com.vibereading.app.data.local.entity.LlmProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LlmProfileDao {

    @Query("SELECT * FROM llm_profiles ORDER BY id ASC")
    fun getAll(): Flow<List<LlmProfileEntity>>

    @Query("SELECT * FROM llm_profiles WHERE isActive = 1 LIMIT 1")
    fun getActive(): Flow<LlmProfileEntity?>

    @Query("SELECT COUNT(*) FROM llm_profiles")
    suspend fun count(): Int

    @Insert
    suspend fun insert(profile: LlmProfileEntity): Long

    @Update
    suspend fun update(profile: LlmProfileEntity)

    @Query("DELETE FROM llm_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM llm_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LlmProfileEntity?

    /** 事务切换活跃配置：取消旧活跃，设置新活跃。 */
    @Transaction
    suspend fun setActive(profileId: Long) {
        clearActive()
        activateById(profileId)
    }

    @Query("UPDATE llm_profiles SET isActive = 0 WHERE isActive = 1")
    suspend fun clearActive()

    @Query("UPDATE llm_profiles SET isActive = 1 WHERE id = :profileId")
    suspend fun activateById(profileId: Long)
}
