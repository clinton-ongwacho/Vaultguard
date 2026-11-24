package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.entity.AccessEventEntity
import com.example.data.entity.AdminConfigEntity
import com.example.data.entity.LimitedAppEntity
import com.example.data.entity.SecurityQuestionEntity

@Database(
    entities = [
        AdminConfigEntity::class,
        LimitedAppEntity::class,
        AccessEventEntity::class,
        SecurityQuestionEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var instance: VaultDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE admin_config ADD COLUMN capturePhotoOnOpenEnabled INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL("ALTER TABLE access_events ADD COLUMN photoPath TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE admin_config ADD COLUMN redirectDisplayName TEXT NOT NULL DEFAULT 'SUSPENDED'"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `security_questions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `question` TEXT NOT NULL,
                        `answerHash` TEXT NOT NULL,
                        `encryptedAnswer` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `vault_media`")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE admin_config ADD COLUMN redirectDecoyMode TEXT NOT NULL DEFAULT 'ICON_DISPLAY'"
                )
                db.execSQL(
                    "ALTER TABLE admin_config ADD COLUMN immediateLockTimeoutSeconds INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE limited_apps ADD COLUMN guardMode TEXT NOT NULL DEFAULT 'RESTRICTED'"
                )
                db.execSQL(
                    "ALTER TABLE limited_apps ADD COLUMN notificationPrivacyMode TEXT NOT NULL DEFAULT 'SILENT_AND_HIDE'"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE admin_config ADD COLUMN appLockType TEXT NOT NULL DEFAULT 'NONE'"
                )
                db.execSQL(
                    "ALTER TABLE admin_config ADD COLUMN appLockSecretHash TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): VaultDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "vault_guard.db",
                )
                    .addMigrations(
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                    )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
