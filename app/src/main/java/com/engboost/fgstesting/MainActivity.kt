package com.engboost.fgstesting

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.tooling.preview.Preview
import com.engboost.fgstesting.ui.theme.FGStestingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FGStestingTheme {
                FgsTestScreen()
            }
        }
    }
}

@Composable
private fun FgsTestScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingMode by rememberSaveable { mutableStateOf<FgsMode?>(null) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingMode?.let { mode ->
            if (hasPermissions(context, mode.requiredPermissions)) startMode(context, mode)
        }
        pendingMode = null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FGS testing — target SDK 36", style = MaterialTheme.typography.headlineSmall)
        Text("Каждая кнопка останавливает предыдущий тестовый FGS и запускает выбранный.")
        FgsMode.entries.forEach { mode ->
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val permissionsToRequest = mode.requiredPermissions + Manifest.permission.POST_NOTIFICATIONS
                    if (hasPermissions(context, permissionsToRequest)) {
                        startMode(context, mode)
                    } else {
                        pendingMode = mode
                        permissionLauncher.launch(permissionsToRequest.toTypedArray())
                    }
                },
            ) {
                Text(mode.buttonTitle)
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { stopAllModes(context) },
        ) {
            Text("Остановить активный тест")
        }
        Text(
            "При первом запуске запрашиваются уведомления. Bluetooth нужен для scan-тестов; " +
                "геолокация — для Location.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun hasPermissions(context: Context, permissions: List<String>): Boolean =
    permissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

private fun startMode(context: Context, mode: FgsMode) {
    stopAllModes(context)
    ContextCompat.startForegroundService(context, Intent(context, mode.serviceClass))
}

private fun stopAllModes(context: Context) {
    FgsMode.entries.forEach { mode -> context.stopService(Intent(context, mode.serviceClass)) }
}

@Preview(showBackground = true)
@Composable
private fun FgsTestScreenPreview() {
    FGStestingTheme {
        FgsTestScreen()
    }
}
