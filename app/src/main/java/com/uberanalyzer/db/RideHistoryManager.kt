package com.uberanalyzer.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.uberanalyzer.model.RideData

data class RideRecord(
    val id: Long = 0,
    val timestamp: Long,
    val price: Double,
    val distance: Double,
    val time: Int,
    val category: String,
    val score: Double,
    val rating: String,
    val rawText: String,
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

class RideHistoryManager(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "ride_history.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_RIDES = "rides"
        
        private const val COLUMN_ID = "id"
        private const val COLUMN_TIME = "timestamp"
        private const val COLUMN_PRICE = "price"
        private const val COLUMN_DIST = "distance"
        private const val COLUMN_DURATION = "duration"
        private const val COLUMN_CAT = "category"
        private const val COLUMN_SCORE = "score"
        private const val COLUMN_RATING = "rating"
        private const val COLUMN_RAW = "raw_text"
        private const val COLUMN_LAT = "lat"
        private const val COLUMN_LON = "lon"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE $TABLE_RIDES (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COLUMN_TIME INTEGER," +
                "$COLUMN_PRICE REAL," +
                "$COLUMN_DIST REAL," +
                "$COLUMN_DURATION INTEGER," +
                "$COLUMN_CAT TEXT," +
                "$COLUMN_SCORE REAL," +
                "$COLUMN_RATING TEXT," +
                "$COLUMN_RAW TEXT," +
                "$COLUMN_LAT REAL DEFAULT 0.0," +
                "$COLUMN_LON REAL DEFAULT 0.0)")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_RIDES ADD COLUMN $COLUMN_LAT REAL DEFAULT 0.0")
                db.execSQL("ALTER TABLE $TABLE_RIDES ADD COLUMN $COLUMN_LON REAL DEFAULT 0.0")
            } catch (e: Exception) {
                db.execSQL("DROP TABLE IF EXISTS $TABLE_RIDES")
                onCreate(db)
            }
        } else {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_RIDES")
            onCreate(db)
        }
    }

    fun saveRide(ride: RideData, score: Double, rating: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TIME, System.currentTimeMillis())
            put(COLUMN_PRICE, ride.price)
            put(COLUMN_DIST, ride.distanceKm)
            put(COLUMN_DURATION, ride.timeMin)
            put(COLUMN_CAT, ride.category.displayName)
            put(COLUMN_SCORE, score)
            put(COLUMN_RATING, rating)
            put(COLUMN_RAW, ride.raw)
            put(COLUMN_LAT, ride.lat)
            put(COLUMN_LON, ride.lon)
        }
        db.insert(TABLE_RIDES, null, values)
        db.close()
    }

    fun getAllRides(): List<RideRecord> {
        val list = mutableListOf<RideRecord>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_RIDES ORDER BY $COLUMN_ID DESC", null)

        if (cursor.moveToFirst()) {
            val latIdx = cursor.getColumnIndex(COLUMN_LAT)
            val lonIdx = cursor.getColumnIndex(COLUMN_LON)
            do {
                list.add(RideRecord(
                    cursor.getLong(0),
                    cursor.getLong(1),
                    cursor.getDouble(2),
                    cursor.getDouble(3),
                    cursor.getInt(4),
                    cursor.getString(5),
                    cursor.getDouble(6),
                    cursor.getString(7),
                    cursor.getString(8),
                    if (latIdx >= 0) cursor.getDouble(latIdx) else 0.0,
                    if (lonIdx >= 0) cursor.getDouble(lonIdx) else 0.0
                ))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return list
    }

    fun clearHistory() {
        val db = this.writableDatabase
        db.delete(TABLE_RIDES, null, null)
        db.close()
    }
}
