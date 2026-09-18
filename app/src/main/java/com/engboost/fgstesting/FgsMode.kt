package com.engboost.fgstesting

import android.Manifest
import android.app.Service

enum class FgsMode(
    val buttonTitle: String,
    val serviceClass: Class<out Service>,
    val requiredPermissions: List<String>,
) {
    SPECIAL_USE_BLE_SCAN(
        buttonTitle = "specialUse: BLE scan",
        serviceClass = SpecialUseBleScanService::class.java,
        requiredPermissions = listOf(Manifest.permission.BLUETOOTH_SCAN),
    ),
    CONNECTED_DEVICE_BLE_SCAN(
        buttonTitle = "connectedDevice: BLE scan",
        serviceClass = ConnectedDeviceBleScanService::class.java,
        requiredPermissions = listOf(Manifest.permission.BLUETOOTH_SCAN),
    ),
    DATA_SYNC(
        buttonTitle = "dataSync: test service",
        serviceClass = DataSyncTestService::class.java,
        requiredPermissions = emptyList(),
    ),
    LOCATION(
        buttonTitle = "location: GPS updates",
        serviceClass = LocationTestService::class.java,
        requiredPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
    ),
}
