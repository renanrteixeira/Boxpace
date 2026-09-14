package com.boxpace.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EncomendaEntity::class, EventoEntity::class, DeltaPendenteEntity::class],
    version = 3,
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

        /**
         * Migração 2→3 (AD-6): sinalização estruturada de entrega nos eventos.
         * A nova coluna `entregue` nasce com a heurística de texto antiga como
         * backfill, para que o comportamento pré-existente seja preservado; a
         * partir daqui o scraper passa a enviar a flag estruturada.
         */
        internal val MIGRACAO_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE eventos ADD COLUMN entregue INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE eventos SET entregue = 1
                    WHERE lower(descricao) LIKE '%entregue%'
                       OR lower(descricao) LIKE '%assinad%'
                       OR lower(descricao) LIKE '%assinatur%'
                       OR lower(descricao) LIKE '%assinar%'
                    """.trimIndent(),
                )
            }
        }

        fun criar(context: Context): EncomendaDatabase =
            Room.databaseBuilder(context, EncomendaDatabase::class.java, NOME)
                .addMigrations(MIGRACAO_1_2, MIGRACAO_2_3)
                .build()
    }
}
