package com.engboost.fgstesting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

private const val CHANNEL_ID = "fgs_test_channel_v2"
private const val LOG_TAG = "FgsTest"

abstract class BaseTestForegroundService : Service() {
    protected abstract val foregroundType: Int
    protected abstract val title: String
    protected abstract val description: String
    protected abstract val notificationId: Int

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(LOG_TAG, "START ${javaClass.simpleName}: startId=$startId, type=$foregroundType")
        createNotificationChannel()
        startForeground(notificationId, createNotification(), foregroundType)
        Log.i(LOG_TAG, "FOREGROUND ${javaClass.simpleName}: notificationId=$notificationId")
        onForegroundStarted()
        return START_STICKY
    }

    protected open fun onForegroundStarted() = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(LOG_TAG, "DESTROY ${javaClass.simpleName}")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FGS test services",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(description)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp)
            .build()
    }
}

abstract class BleScanForegroundService : BaseTestForegroundService() {
    private val scanner get() = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(title, "BLE device: ${result.device.address}, RSSI=${result.rssi}")
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(title, "BLE scan failed: $errorCode")
        }
    }

    override fun onForegroundStarted() {
        val activeScanner = scanner
        if (activeScanner == null) {
            Log.w(LOG_TAG, "${javaClass.simpleName}: Bluetooth LE scanner is unavailable")
            return
        }
        runCatching { activeScanner.startScan(callback) }
            .onSuccess { Log.i(LOG_TAG, "${javaClass.simpleName}: BLE scan started") }
            .onFailure { Log.e(title, "Unable to start BLE scan", it) }
    }

    override fun onDestroy() {
        runCatching { scanner?.stopScan(callback) }
        super.onDestroy()
    }
}

class SpecialUseBleScanService : BleScanForegroundService() {
    override val foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    override val title = "specialUse FGS: BLE scan"
    override val description = "Test-only Bluetooth LE discovery is active"
    override val notificationId = 1101
}

class ConnectedDeviceBleScanService : BleScanForegroundService() {
    override val foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    override val title = "connectedDevice FGS: BLE scan"
    override val description = "Bluetooth LE discovery is active"
    override val notificationId = 1102
}

class DataSyncTestService : BaseTestForegroundService() {
    override val foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    override val title = "dataSync FGS"
    override val description = "Data synchronization test service is active"
    override val notificationId = 1103

    override fun onForegroundStarted() {
        Log.i(LOG_TAG, "${javaClass.simpleName}: active; no network synchronization is configured")
    }
}

class LocationTestService : BaseTestForegroundService(), LocationListener {
    override val foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    override val title = "location FGS"
    override val description = "GPS location updates are active"
    override val notificationId = 1104

    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    override fun onForegroundStarted() {
        Log.i(LOG_TAG, "${javaClass.simpleName}: requesting GPS updates")
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                this,
            )
        }.onFailure { Log.e(title, "Unable to request location updates", it) }
    }

    override fun onLocationChanged(location: Location) {
        Log.d(LOG_TAG, "${javaClass.simpleName}: location=${location.latitude},${location.longitude}")
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        super.onDestroy()
    }
}
