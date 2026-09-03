package com.boxpace.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EncomendaEntity::class, EventoEntity::class, DeltaPendenteEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class EncomendaDatabase : RoomDatabase() {
    abstract fun encomendaDao(): EncomendaDao
    abstract fun deltaPendenteDao(): DeltaPendenteDao

    companion object {
        private const val NOME = "boxpace.db"

        fun criar(context: Context): EncomendaDatabase =
            Room.databaseBuilder(context, EncomendaDatabase::class.java, NOME).build()
    }
}
