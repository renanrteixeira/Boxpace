package com.boxpace.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
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
                        criaSchemaV1(db)
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

    private fun criaSchemaV1(db: SupportSQLiteDatabase) {
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

    private fun migracao_2_3_sobre(db: SupportSQLiteDatabase, eventos: String) {
        // Simula v1→v2 (buscasSemEventos presente) antes do backfill da 2→3.
        db.execSQL("ALTER TABLE encomendas ADD COLUMN buscasSemEventos INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "INSERT INTO encomendas " +
                "(id,codigo,transportadora,etiqueta,ultimoStatus,statusEntregue,criadaEm,atualizadaEm,fechadaEm,cpfDestinatario,buscasSemEventos) " +
                "VALUES " +
                "('correios:AA999999999BR','AA999999999BR','correios','Caixa',NULL,0,'2026-09-01T12:00:00Z','2026-09-01T12:00:00Z',NULL,NULL,0)",
        )
        db.execSQL(
            "CREATE TABLE eventos (" +
                "idEncomenda TEXT NOT NULL," +
                "data TEXT NOT NULL," +
                "descricao TEXT NOT NULL," +
                "cidade TEXT," +
                "uf TEXT," +
                "unidade TEXT NOT NULL," +
                "PRIMARY KEY(idEncomenda, data, descricao, unidade))",
        )
        db.execSQL(eventos)
        EncomendaDatabase.MIGRACAO_2_3.migrate(db)
    }

    private fun entregueDe(db: SupportSQLiteDatabase, id: String, descricao: String): Int {
        val cursor = db.query(
            "SELECT entregue FROM eventos WHERE idEncomenda = ? AND descricao = ?",
            arrayOf(id, descricao),
        )
        cursor.moveToFirst()
        val valor = cursor.getInt(0)
        cursor.close()
        return valor
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

    @Test
    fun migracao_2_3_adiciona_coluna_entregue_e_faz_backfill_pela_heuristica_antiga() {
        val db = helper.writableDatabase
        migracao_2_3_sobre(
            db,
            """
            INSERT INTO eventos (idEncomenda,data,descricao,cidade,uf,unidade) VALUES
            ('correios:AA999999999BR','2026-09-01T10:00:00','Objeto postado',NULL,NULL,''),
            ('correios:AA999999999BR','2026-09-01T11:00:00','Objeto entregue ao destinatário',NULL,NULL,''),
            ('correios:AA999999999BR','2026-09-01T12:00:00','O pacote foi assinado!',NULL,NULL,''),
            ('correios:AA999999999BR','2026-09-01T13:00:00','Objeto devolvido ao remetente',NULL,NULL,'')
            """.trimIndent(),
        )

        assertEquals(0, entregueDe(db, "correios:AA999999999BR", "Objeto postado"))
        assertEquals(1, entregueDe(db, "correios:AA999999999BR", "Objeto entregue ao destinatário"))
        assertEquals(1, entregueDe(db, "correios:AA999999999BR", "O pacote foi assinado!"))
        // Sinalização estruturada ainda inexistente no legado: devolvido segue 0
        // (preserva o comportamento antigo; o scraper passa a marcá-lo como flag).
        assertEquals(0, entregueDe(db, "correios:AA999999999BR", "Objeto devolvido ao remetente"))
    }
}