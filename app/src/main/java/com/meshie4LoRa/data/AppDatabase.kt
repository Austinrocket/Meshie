package com.meshie4LoRa.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        NodeEntity::class, MessageEntity::class, ModelEntity::class, CharacterEntity::class, DecisionEntity::class,
        MemoryEntity::class, RelationshipEntity::class, FeelingLogEntity::class, PositionEntity::class, CooldownEntity::class,
    ],
    version = 13,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nodes(): NodeDao
    abstract fun messages(): MessageDao
    abstract fun models(): ModelDao
    abstract fun characters(): CharacterDao
    abstract fun decisions(): DecisionDao
    abstract fun memories(): MemoryDao
    abstract fun relationships(): RelationshipDao
    abstract fun positions(): PositionDao
    abstract fun cooldowns(): CooldownDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "meshchan.db").build()
    }
}
