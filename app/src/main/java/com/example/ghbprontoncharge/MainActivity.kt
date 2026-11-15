package com.example.ghbprontoncharge

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ghbprontoncharge.service.ChargingMonitorService
import com.example.ghbprontoncharge.ui.ChargingActivity
import com.example.ghbprontoncharge.ui.theme.GHBProntonChargeTheme
import com.example.ghbprontoncharge.util.PreferencesManager

class MainActivity : ComponentActivity() {

    private var hasOverlayPermission by mutableStateOf(false)
    private var isBatteryOptIgnored by mutableStateOf(false)
    private var canUseFullScreenIntent by mutableStateOf(true)
    private var hasNotificationPermission by mutableStateOf(true)
    private var isSoundEnabled by mutableStateOf(true)
    private var isServiceEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshStates()

        // Auto start service on launch if enabled and permitted
        if (isServiceEnabled && hasOverlayPermission) {
            ChargingMonitorService.startService(this)
        }

        setContent {
            val postNotificationLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                hasNotificationPermission = granted
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            GHBProntonChargeTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0A0B0E)
                ) { innerPadding ->
                    MainContent(
                        paddingValues = innerPadding,
                        hasOverlayPermission = hasOverlayPermission,
                        isBatteryOptIgnored = isBatteryOptIgnored,
                        canUseFullScreenIntent = canUseFullScreenIntent,
                        hasNotificationPermission = hasNotificationPermission,
                        isSoundEnabled = isSoundEnabled,
                        isServiceEnabled = isServiceEnabled,
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onRequestBatteryOptimization = { requestBatteryOptimizationExclusion() },
                        onRequestFullScreenIntentPermission = { requestFullScreenIntentPermission() },
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onSoundToggle = { enabled ->
                            isSoundEnabled = enabled
                            PreferencesManager.setSoundEnabled(this, enabled)
                        },
                        onServiceToggle = { enabled ->
                            isServiceEnabled = enabled
                            PreferencesManager.setServiceEnabled(this, enabled)
                            if (enabled) {
                                if (Settings.canDrawOverlays(this)) {
                                    ChargingMonitorService.startService(this)
                                } else {
                                    Toast.makeText(this, "Permission de superposition requise", Toast.LENGTH_SHORT).show()
                                    requestOverlayPermission()
                                }
                            } else {
                                ChargingMonitorService.stopService(this)
                            }
                        },
                        onTestPack = {
                            if (Settings.canDrawOverlays(this)) {
                                ChargingMonitorService.showOverlay(this)
                            } else {
                                val intent = Intent(this, ChargingActivity::class.java)
                                startActivity(intent)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
        if (isServiceEnabled && hasOverlayPermission) {
            ChargingMonitorService.startService(this)
        }
    }

    private fun refreshStates() {
        hasOverlayPermission = Settings.canDrawOverlays(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryOptIgnored = powerManager.isIgnoringBatteryOptimizations(packageName)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        canUseFullScreenIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager.canUseFullScreenIntent()
        } else {
            true
        }

        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        isSoundEnabled = PreferencesManager.isSoundEnabled(this)
        isServiceEnabled = PreferencesManager.isServiceEnabled(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExclusion() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
fun MainContent(
    paddingValues: PaddingValues,
    hasOverlayPermission: Boolean,
    isBatteryOptIgnored: Boolean,
    canUseFullScreenIntent: Boolean,
    hasNotificationPermission: Boolean,
    isSoundEnabled: Boolean,
    isServiceEnabled: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onRequestFullScreenIntentPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSoundToggle: (Boolean) -> Unit,
    onServiceToggle: (Boolean) -> Unit,
    onTestPack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title Header
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161A23)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "☢ PROTON PACK CHARGE ☢",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00FFFF)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Superposition de Recharge Ghostbusters",
                    fontSize = 13.sp,
                    color = Color(0xFFAAAABB)
                )
            }
        }

        // Card 1: Overlay Permission
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161A23)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "1. Perm. Superposition",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    StatusBadge(isOk = hasOverlayPermission)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Requis pour afficher l'animation par-dessus les applications et l'écran de verrouillage.",
                    fontSize = 12.sp,
                    color = Color(0xFF888899)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequestOverlayPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasOverlayPermission) Color(0xFF2E3545) else Color(0xFF0088FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (hasOverlayPermission) "Permission déjà accordée" else "Accorder la permission",
                        color = Color.White
                    )
                }
            }
        }

        // Card 2: Battery Optimization
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161A23)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "2. Optimisation Batterie",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    StatusBadge(isOk = isBatteryOptIgnored)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Désactiver l'optimisation empêche le système de tuer la surveillance de recharge en arrière-plan.",
                    fontSize = 12.sp,
                    color = Color(0xFF888899)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequestBatteryOptimization,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBatteryOptIgnored) Color(0xFF2E3545) else Color(0xFF0088FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isBatteryOptIgnored) "Optimisation ignorée" else "Demander l'exclusion batterie",
                        color = Color.White
                    )
                }
            }
        }

        // Card 3: Notifications Plein Écran (Full Screen Intent)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161A23)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "3. Notifications plein écran",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    StatusBadge(isOk = canUseFullScreenIntent && hasNotificationPermission)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Permet au service d'ouvrir l'animation en plein écran lors du branchement quand le téléphone est verrouillé.",
                    fontSize = 12.sp,
                    color = Color(0xFF888899)
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (!hasNotificationPermission) {
                    Button(
                        onClick = onRequestNotificationPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088FF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Autoriser les notifications", color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Button(
                        onClick = onRequestFullScreenIntentPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (canUseFullScreenIntent) Color(0xFF2E3545) else Color(0xFF0088FF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (canUseFullScreenIntent) "Plein écran autorisé" else "Gérer les alertes plein écran",
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Card 4: Options & Service
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161A23)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "4. Paramètres & Service",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Service Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Service de surveillance", color = Color.White, fontSize = 14.sp)
                        Text(text = "Détecte le branchement secteur", color = Color(0xFF888899), fontSize = 11.sp)
                    }
                    Switch(
                        checked = isServiceEnabled,
                        onCheckedChange = onServiceToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF00FFFF)
                        )
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF2A2E3B))

                // Sound Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Son au démarrage", color = Color.White, fontSize = 14.sp)
                        Text(text = "Démarrage audio si ringer en mode Normal", color = Color(0xFF888899), fontSize = 11.sp)
                    }
                    Switch(
                        checked = isSoundEnabled,
                        onCheckedChange = onSoundToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF00FFFF)
                        )
                    )
                }
            }
        }

        // Card 5: Manual Test Button
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1424)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Test Manuel",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF66CC)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onTestPack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0055)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "⚡ TESTER LE PACK (8s) ⚡",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(isOk: Boolean) {
    Surface(
        color = if (isOk) Color(0xFF1E3A2B) else Color(0xFF3D1E1E),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = if (isOk) "✔ ACCORDÉE" else "✖ REQUISE",
            color = if (isOk) Color(0xFF00FF88) else Color(0xFFFF4444),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
