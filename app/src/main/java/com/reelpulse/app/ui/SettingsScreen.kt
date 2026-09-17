package com.reelpulse.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.reelpulse.app.auth.AuthManager

@Composable
fun SettingsScreen(padding: PaddingValues) {
    var showProfileDialog by remember { mutableStateOf(value = false) }
    var showAboutDialog by remember { mutableStateOf(value = false) }
    var showPrivacyDialog by remember { mutableStateOf(value = false) }
    val context = LocalContext.current
    val user by AuthManager.user.collectAsState(initial = null)
    
    val prefs = remember { context.getSharedPreferences("reel_pulse_prefs", Context.MODE_PRIVATE) }
    var dailyLimit by remember { mutableFloatStateOf(prefs.getInt("daily_reel_limit", 50).toFloat()) }
    var usageAlertsEnabled by remember { mutableStateOf(prefs.getBoolean("usage_alerts_enabled", true)) }

    if (showProfileDialog) {
        AlertDialog(
            onDismissRequest = { 
                prefs.edit { putInt("daily_reel_limit", dailyLimit.toInt()) }
                showProfileDialog = false 
            },
            title = { Text("Profile & Goals") },
            text = {
                Column {
                    Text("Logged in as:", style = MaterialTheme.typography.labelMedium)
                    Text(user ?: "Anonymous", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    
                    Text("Daily Reel Limit: ${dailyLimit.toInt()}", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = dailyLimit,
                        onValueChange = { dailyLimit = it },
                        valueRange = 10f..200f,
                        steps = 19,
                    )
                    Text(
                        "We'll notify you once you reach this limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { 
                        prefs.edit { putInt("daily_reel_limit", dailyLimit.toInt()) }
                        showProfileDialog = false 
                    },
                ) {
                    Text("Save")
                }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About ReelPulse") },
            text = {
                Column {
                    Text(
                        "ReelPulse is your companion for mindful digital consumption. " +
                        "We help you protect your neural health by reducing mindless scrolling, " +
                        "allowing your brain to focus on what truly matters for a productive life.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Version: 1.0.0", fontWeight = FontWeight.Bold)
                    Text("Focusing on your Digital Wellbeing.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Stay Productive")
                }
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Privacy & Brain Health") },
            text = {
                Column {
                    Text(
                        "Your privacy is essential for a stress-free life. " +
                        "ReelPulse processes all tracking data locally on your device. " +
                        "We never upload your usage history or video titles to any server.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "• Data stays on device\n" +
                        "• No third-party tracking\n" +
                        "• Designed for neural wellbeing",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text("I Understand")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }

        item {
            SettingsCategory(title = "Account")
        }
        item {
            SettingsItem(
                icon = Icons.Default.Person,
                title = "Profile & Goals",
                subtitle = "Daily limit: ${dailyLimit.toInt()} reels",
            ) { showProfileDialog = true }
        }
        item {
            SettingsItem(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                title = "Log Out",
                subtitle = "Sign out of ReelPulse Tracker",
                onClick = { AuthManager.signOut(context) }
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            SettingsCategory(title = "App Settings")
        }
        item {
            SettingsToggleItem(
                icon = Icons.Default.Notifications,
                title = "Usage Alerts",
                subtitle = "Get notified when your usage is too high",
                checked = usageAlertsEnabled,
                onCheckedChange = {
                    usageAlertsEnabled = it
                    prefs.edit().putBoolean("usage_alerts_enabled", it).apply()
                }
            )
        }
        item {
            SettingsItem(
                icon = Icons.Default.Security,
                title = "Privacy",
                subtitle = "How we protect your neural data",
                onClick = { showPrivacyDialog = true }
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            SettingsCategory(title = "Support")
        }
        item {
            SettingsItem(
                icon = Icons.Default.Info,
                title = "About",
                subtitle = "Version 1.0.0",
                onClick = { showAboutDialog = true }
            )
        }
    }
}

@Composable
fun SettingsCategory(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
