package com.uberanalyzer.settings

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Banco local dos pontos de interesse. A primeira abertura importa o JSON antigo
 * salvo em SharedPreferences para que a atualização preserve os cadastros existentes.
 */
class InterestPointDatabase(context: Context) : SQLiteOpenHelper(context, "indrive_analyzer.db", null, 2) {
    private val legacyPrefs = context.getSharedPreferences("uber_analyzer_prefs", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE interest_points (id INTEGER PRIMARY KEY CHECK (id = 1), points_json TEXT NOT NULL)")
        createDestinationAddressHistory(db)
        migrateLegacy(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDestinationAddressHistory(db)
    }

    private fun createDestinationAddressHistory(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS destination_filter_addresses (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "address TEXT NOT NULL COLLATE NOCASE UNIQUE, " +
                "created_at INTEGER NOT NULL)"
        )
    }

    private fun migrateLegacy(db: SQLiteDatabase) {
        val legacy = legacyPrefs.getString(SettingsManager.KEY_MAP_INTEREST_POINTS, null)?.trim()
        if (!legacy.isNullOrBlank() && legacy != "[]") {
            db.execSQL("INSERT OR REPLACE INTO interest_points(id, points_json) VALUES(1, ?)", arrayOf(legacy))
        }
    }

    fun read(): String {
        readableDatabase.rawQuery("SELECT points_json FROM interest_points WHERE id = 1", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else "[]"
        }
    }

    fun write(json: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO interest_points(id, points_json) VALUES(1, ?)", arrayOf(json))
    }

    fun saveDestinationFilterAddress(address: String) {
        val cleanAddress = address.trim()
        if (cleanAddress.isBlank()) return
        val values = android.content.ContentValues().apply {
            put("address", cleanAddress)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "destination_filter_addresses",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun getDestinationFilterAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        readableDatabase.query(
            "destination_filter_addresses",
            arrayOf("address"),
            null,
            null,
            null,
            null,
            "id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) addresses.add(cursor.getString(0))
        }
        return addresses
    }
}
