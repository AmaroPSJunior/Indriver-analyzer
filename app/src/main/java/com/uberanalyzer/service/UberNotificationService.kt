package com.uberanalyzer.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.uberanalyzer.analyzer.RideAnalyzer
import com.uberanalyzer.parser.RideParser

class UberNotificationService : NotificationListenerService() {
    companion object {
        var isRunning = false
        var lastCapturedText = "Nenhuma notificação detectada ainda."
        var notificationCount = 0
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isRunning = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isRunning = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        
        if (pkg.contains("indriver", ignoreCase = true) || pkg.contains("uber", ignoreCase = true)) {
            notificationCount++
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val fullText = "APP: $pkg\nTÍTULO: $title\nTEXTO: $text"
            
            lastCapturedText = fullText

            RideParser.parse("$title $text")?.let { rideData ->
                val analysis = RideAnalyzer.analyze(rideData)
                val intent = Intent("com.uberanalyzer.ACTION_INDRIVE_ROUTE_DETECTED").apply {
                    setPackage(packageName)
                    putExtra("pickup_address", rideData.pickupAddress ?: "Embarque detectado")
                    putExtra("dropoff_address", rideData.dropoffAddress ?: "Destino detectado")
                    putExtra("price", rideData.price)
                    putExtra("distance_km", rideData.distanceKm)
                    putExtra("time_min", rideData.timeMin)
                    putExtra("earnings_km", if (rideData.distanceKm > 0) rideData.price / rideData.distanceKm else 0.0)
                    putExtra("score", analysis.score)
                    putExtra("rating", analysis.rating.name)
                    putExtra("raw_text", fullText)
                }
                sendBroadcast(intent)
            }
        }
    }
}
