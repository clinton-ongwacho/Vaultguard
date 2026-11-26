package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.entity.AccessEventEntity
import com.example.data.entity.AdminConfigEntity
import com.example.data.entity.LimitedAppEntity
import com.example.data.entity.RestrictedAppStateEntity
import com.example.data.entity.SecurityQuestionEntity
import kotlinx.coroutines.flow.Flow

data class AppRestrictionFrequency(
    val packageName: String,
    val eventCount: Int,
    val lastEventAtMillis: Long,
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM admin_config WHERE id = 1")
    fun observeConfig(): Flow<AdminConfigEntity?>

    @Query("SELECT * FROM admin_config WHERE id = 1")
    suspend fun getConfigOnce(): AdminConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: AdminConfigEntity)

    @Query("SELECT * FROM limited_apps ORDER BY appName ASC")
    fun observeLimitedApps(): Flow<List<LimitedAppEntity>>

    @Query("SELECT * FROM limited_apps")
    suspend fun getLimitedAppsOnce(): List<LimitedAppEntity>

    @Query("SELECT * FROM limited_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getLimitedApp(packageName: String): LimitedAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLimitedApp(app: LimitedAppEntity)

    @Query("DELETE FROM limited_apps WHERE packageName = :packageName")
    suspend fun deleteLimitedApp(packageName: String)

    @Query("SELECT * FROM restricted_app_states")
    fun observeRestrictedAppStates(): Flow<List<RestrictedAppStateEntity>>

    @Query("SELECT * FROM restricted_app_states")
    suspend fun getRestrictedAppStatesOnce(): List<RestrictedAppStateEntity>

    @Query("SELECT * FROM restricted_app_states WHERE packageName = :packageName LIMIT 1")
    suspend fun getRestrictedAppState(packageName: String): RestrictedAppStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRestrictedAppState(state: RestrictedAppStateEntity)

    @Query("DELETE FROM restricted_app_states WHERE packageName = :packageName")
    suspend fun deleteRestrictedAppState(packageName: String)

    @Query("SELECT * FROM access_events ORDER BY timestampMillis DESC LIMIT 60")
    fun observeRecentEvents(): Flow<List<AccessEventEntity>>

    @Query(
        """
        SELECT packageName, COUNT(*) AS eventCount, MAX(timestampMillis) AS lastEventAtMillis
        FROM access_events
        WHERE eventType IN ('APP_RESTRICTED', 'RESTRICTED_GATE', 'SESSION_UNLOCKED')
        GROUP BY packageName
        ORDER BY eventCount DESC, lastEventAtMillis DESC
        """
    )
    fun observeRestrictionEventFrequencies(): Flow<List<AppRestrictionFrequency>>

    @Insert
    suspend fun insertEvent(event: AccessEventEntity): Long

    @Query("UPDATE access_events SET photoPath = :photoPath WHERE id = :eventId")
    suspend fun updateEventPhoto(eventId: Long, photoPath: String)

    @Query("DELETE FROM access_events")
    suspend fun clearEvents()

    @Query("SELECT * FROM security_questions ORDER BY id ASC")
    fun observeSecurityQuestions(): Flow<List<SecurityQuestionEntity>>

    @Query("SELECT * FROM security_questions ORDER BY id ASC")
    suspend fun getSecurityQuestionsOnce(): List<SecurityQuestionEntity>

    @Insert
    suspend fun insertSecurityQuestions(questions: List<SecurityQuestionEntity>)

    @Query("DELETE FROM security_questions")
    suspend fun clearSecurityQuestions()

    @Transaction
    suspend fun replaceSecurityQuestions(questions: List<SecurityQuestionEntity>) {
        clearSecurityQuestions()
        insertSecurityQuestions(questions)
    }
}
