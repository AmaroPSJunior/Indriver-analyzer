package com.uberanalyzer.settings

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Banco local dos pontos de interesse. A primeira abertura importa o JSON antigo
 * salvo em SharedPreferences para que a atualização preserve os cadastros existentes.
 */
class InterestPointDatabase(context: Context) : SQLiteOpenHelper(context, "indrive_analyzer.db", null, 3) {
    private val legacyPrefs = context.getSharedPreferences("uber_analyzer_prefs", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE interest_points (id INTEGER PRIMARY KEY CHECK (id = 1), points_json TEXT NOT NULL)")
        createDestinationAddressHistory(db)
        migrateLegacy(db)
        syncAddressRegistries(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDestinationAddressHistory(db)
        if (oldVersion < 3) syncAddressRegistries(db)
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
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO interest_points(id, points_json) VALUES(1, ?)", arrayOf(json))
            syncHistoryFromPoints(db, parsePoints(json))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveDestinationFilterAddress(address: String) {
        val cleanAddress = address.trim()
        if (cleanAddress.isBlank()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val points = parsePoints(readPoints(db))
            var existing: JSONObject? = null
            for (index in 0 until points.length()) {
                val point = points.optJSONObject(index) ?: continue
                if (point.optString("address").trim().equals(cleanAddress, ignoreCase = true)) {
                    existing = point
                    break
                }
            }
            if (existing == null) {
                points.put(JSONObject().apply {
                    put("name", cleanAddress)
                    put("address", cleanAddress)
                    put("icon", "📍")
                    put("visible", true)
                })
            } else {
                existing?.put("visible", true)
            }
            db.execSQL("INSERT OR REPLACE INTO interest_points(id, points_json) VALUES(1, ?)", arrayOf(points.toString()))
            syncHistoryFromPoints(db, points)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getDestinationFilterAddresses(): List<String> {
        syncAddressRegistries()
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

    fun syncAddressRegistries() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            syncAddressRegistries(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun syncAddressRegistries(db: SQLiteDatabase) {
        val points = parsePoints(readPoints(db))
        val seen = mutableSetOf<String>()
        for (index in 0 until points.length()) {
            val address = points.optJSONObject(index)?.optString("address")?.trim().orEmpty()
            if (address.isNotBlank()) seen.add(address.lowercase(Locale.ROOT))
        }
        db.rawQuery("SELECT address FROM destination_filter_addresses ORDER BY id ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(0).trim()
                if (address.isNotBlank() && seen.add(address.lowercase(Locale.ROOT))) {
                    points.put(JSONObject().apply {
                        put("name", address)
                        put("address", address)
                        put("icon", "📍")
                        put("visible", true)
                    })
                }
            }
        }
        db.execSQL("INSERT OR REPLACE INTO interest_points(id, points_json) VALUES(1, ?)", arrayOf(points.toString()))
        syncHistoryFromPoints(db, points)
    }

    private fun syncHistoryFromPoints(db: SQLiteDatabase, points: JSONArray) {
        val desired = linkedMapOf<String, String>()
        for (i in 0 until points.length()) {
            val address = points.optJSONObject(i)?.optString("address")?.trim().orEmpty()
            if (address.isNotBlank()) desired.putIfAbsent(address.lowercase(Locale.ROOT), address)
        }

        val existing = mutableMapOf<String, String>()
        db.rawQuery("SELECT address FROM destination_filter_addresses", null).use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(0)
                existing[address.lowercase(Locale.ROOT)] = address
            }
        }
        existing.forEach { (key, value) ->
            if (!desired.containsKey(key)) {
                db.delete("destination_filter_addresses", "address = ?", arrayOf(value))
            }
        }
        desired.forEach { (key, address) ->
            if (!existing.containsKey(key)) {
                val values = android.content.ContentValues().apply {
                    put("address", address)
                    put("created_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("destination_filter_addresses", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    private fun readPoints(db: SQLiteDatabase): String =
        db.rawQuery("SELECT points_json FROM interest_points WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "[]"
        }

    private fun parsePoints(json: String): JSONArray = try {
        JSONArray(json)
    } catch (_: Exception) {
        JSONArray()
    }
}
