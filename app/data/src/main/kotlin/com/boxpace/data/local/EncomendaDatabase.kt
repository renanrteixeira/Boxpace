package com.boxpace.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EncomendaEntity::class, EventoEntity::class, DeltaPendenteEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class EncomendaDatabase : RoomDatabase() {
    abstract fun encomendaDao(): EncomendaDao
    abstract fun deltaPendenteDao(): DeltaPendenteDao

    companion object {
        private const val NOME = "boxpace.db"

        /** Exposto como `internal` para o teste de migração em Robolectric. */
        internal val MIGRACAO_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Contador de buscas sem eventos (badge "Sem dados", AC 1.3) —
                // backfill com 0 para encomendas existentes.
                db.execSQL("ALTER TABLE encomendas ADD COLUMN buscasSemEventos INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun criar(context: Context): EncomendaDatabase =
            Room.databaseBuilder(context, EncomendaDatabase::class.java, NOME)
                .addMigrations(MIGRACAO_1_2)
                .build()
    }
}
