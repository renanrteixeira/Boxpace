package com.boxpace.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncomendaDatabaseMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration_test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Schema da v1 (antes do campo buscasSemEventos).
                        db.execSQL(
                            "CREATE TABLE encomendas (" +
                                "id TEXT NOT NULL PRIMARY KEY," +
                                "codigo TEXT NOT NULL," +
                                "transportadora TEXT NOT NULL," +
                                "etiqueta TEXT NOT NULL," +
                                "ultimoStatus TEXT," +
                                "statusEntregue INTEGER NOT NULL," +
                                "criadaEm TEXT NOT NULL," +
                                "atualizadaEm TEXT NOT NULL," +
                                "fechadaEm TEXT," +
                                "cpfDestinatario TEXT)",
                        )
                        db.execSQL(
                            "INSERT INTO encomendas " +
                                "(id,codigo,transportadora,etiqueta,ultimoStatus,statusEntregue,criadaEm,atualizadaEm,fechadaEm,cpfDestinatario) " +
                                "VALUES " +
                                "('correios:AA123456789BR','AA123456789BR','correios','Fone de ouvido',NULL,0,'2026-09-01T12:00:00Z','2026-09-01T12:00:00Z',NULL,NULL)",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        EncomendaDatabase.MIGRACAO_1_2.migrate(db)
                    }
                })
                .build(),
        )
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun migracao_1_2_adiciona_coluna_e_faz_backfill_com_0() {
        val db = helper.writableDatabase

        EncomendaDatabase.MIGRACAO_1_2.migrate(db)

        val cursor = db.query("SELECT buscasSemEventos FROM encomendas WHERE id = 'correios:AA123456789BR'")
        cursor.moveToFirst()
        assertEquals(0, cursor.getInt(0))
        cursor.close()
    }
}
