package com.reelpulse.app.ui

import android.app.StatusBarManager
import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelpulse.app.R
import com.reelpulse.app.auth.AuthManager
import com.reelpulse.app.data.AppCount
import com.reelpulse.app.data.DayCount
import com.reelpulse.app.data.ReelDatabase
import com.reelpulse.app.data.ReelEvent
import com.reelpulse.app.data.ReelRepository
import com.reelpulse.app.service.QuickPauseTileService
import com.reelpulse.app.service.ReelAccessibilityService
import com.reelpulse.app.ui.components.BrainVisualizer
import com.reelpulse.app.ui.theme.ReelPulseTheme
import com.reelpulse.app.util.ServiceUtils
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: ReelRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = ReelDatabase.getInstance(applicationContext)
        repository = ReelRepository(db.reelDao())
        AuthManager.init(this)

        setContent {
            ReelPulseTheme {
                val user by AuthManager.user.collectAsStateWithLifecycle()

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (user == null) {
                        AuthScreen()
                    } else {
                        HomeScreen(
                            repository = repository,
                            onEnableAccessibility = { openAccessibilitySettings() },
                            onEnableOverlay = { openOverlaySettings() },
                        )
                    }
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: ReelRepository,
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("reel_pulse_prefs", Context.MODE_PRIVATE) }

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        if (!phoneStateGranted) {
            Toast.makeText(context, "Call detection requires phone state permission.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) && 
            (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    var isServiceEnabled by remember {
        mutableStateOf(ServiceUtils.isAccessibilityServiceEnabled(context, ReelAccessibilityService::class.java))
    }

    var showFloatingCounter by remember {
        mutableStateOf(prefs.getBoolean("show_floating_counter", false))
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    // Refresh service status when returning to app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceEnabled = ServiceUtils.isAccessibilityServiceEnabled(context, ReelAccessibilityService::class.java)
                if (showFloatingCounter && !Settings.canDrawOverlays(context)) {
                    // Do nothing
                } else if (!showFloatingCounter && prefs.getBoolean("show_floating_counter", false)) {
                     showFloatingCounter = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val todayCount by repository.todayCount().collectAsStateWithLifecycle(initialValue = 0)
    val byApp by repository.todayCountByApp().collectAsStateWithLifecycle(initialValue = emptyList())
    val trend by repository.last7DaysTrend().collectAsStateWithLifecycle(initialValue = emptyList())
    
    var showOnboarding by remember { 
        mutableStateOf(!isServiceEnabled || (showFloatingCounter && !Settings.canDrawOverlays(context))) 
    }

    if (showOnboarding) {
        AlertDialog(
            onDismissRequest = { showOnboarding = false },
            title = { 
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Default.HealthAndSafety,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Secure Your Neural Health", textAlign = TextAlign.Center)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "To shield your brain from mindless scrolling, ReelPulse needs two vital permissions:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("1. Accessibility Scanner", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            Text("Detects when you are on a reel to start tracking focus.", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("2. Heads-up Display", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            Text("Shows your real-time session count on top of videos.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showOnboarding = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Get Started")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_logo),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Black)) {
                                    append("Reel")
                                }
                                withStyle(SpanStyle(fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.primary)) {
                                    append("Pulse")
                                }
                            },
                            letterSpacing = (-1).sp
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                    label = { Text("Stats") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Spa, contentDescription = null) },
                    label = { Text("Detox") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> StatsScreen(
                padding = padding,
                isServiceEnabled = isServiceEnabled,
                onEnableAccessibility = onEnableAccessibility,
                todayCount = todayCount,
                byApp = byApp,
                showFloatingCounter = showFloatingCounter,
                onFloatingCounterToggle = { checked ->
                    if (checked && !Settings.canDrawOverlays(context)) {
                        prefs.edit { putBoolean("show_floating_counter", true) }
                        onEnableOverlay()
                    } else {
                        showFloatingCounter = checked
                        prefs.edit { putBoolean("show_floating_counter", checked) }
                    }
                }
            )
            1 -> HistoryScreen(padding, trend, prefs.getInt("daily_reel_limit", 50))
            2 -> DetoxScreen(padding, trend)
            3 -> SettingsScreen(padding)
        }
    }
}

@Composable
fun DetoxScreen(padding: PaddingValues, trend: List<DayCount>) {
    val streakCount = remember(trend) {
        var streak = 0
        for (day in trend.reversed()) {
            if (day.count < 50) streak++ else break
        }
        streak.coerceAtLeast(1)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Dopamine Detox & Badges",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "🔥", fontSize = 48.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "$streakCount-Day Mindful Streak",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "You are successfully protecting your neural energy and neural focus.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Text(
                "Achievement Badges",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BadgeItem(
                    emoji = "🛡️",
                    title = "Neural Shield",
                    unlocked = streakCount >= 1,
                    modifier = Modifier.weight(1f)
                )
                BadgeItem(
                    emoji = "🧘",
                    title = "Zen Master",
                    unlocked = streakCount >= 3,
                    modifier = Modifier.weight(1f)
                )
                BadgeItem(
                    emoji = "🧠",
                    title = "Dopamine King",
                    unlocked = streakCount >= 7,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Text(
                "Detox Strategies",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            DetoxTipCard(
                title = "Grayscale Mode",
                description = "Turn your phone display to grayscale to make short-form video feeds unappealing to your brain's dopamine receptors."
            )
        }

        item {
            DetoxTipCard(
                title = "The 20-20-20 Rule",
                description = "Every 20 minutes of focus, look at something 20 feet away for 20 seconds to reset neural fatigue."
            )
        }

        item {
            DetoxTipCard(
                title = "Friction Principle",
                description = "Keep your short-form apps inside deep folder layers or use the Quick Pause Tile before opening them."
            )
        }

        item {
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun BadgeItem(emoji: String, title: String, unlocked: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = if (unlocked) 3.dp else 0.dp,
        color = if (unlocked) MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = emoji, fontSize = 32.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (unlocked) "Unlocked" else "Locked",
                style = MaterialTheme.typography.labelSmall,
                color = if (unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
fun DetoxTipCard(title: String, description: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun StatsScreen(
    padding: PaddingValues,
    isServiceEnabled: Boolean,
    onEnableAccessibility: () -> Unit,
    todayCount: Int,
    byApp: List<AppCount>,
    showFloatingCounter: Boolean,
    onFloatingCounterToggle: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ServiceStatusCard(
                isEnabled = isServiceEnabled,
                onClick = onEnableAccessibility
            )
        }

        item {
            MindfulnessTipCard()
        }

        item {
            TodaySummaryCard(todayCount)
        }

        item {
            Text(
                "App Usage Breakdown",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        items(byApp) { appCount ->
            AppBreakdownItem(appCount)
        }

        item {
            FloatingCounterSettingsCard(
                checked = showFloatingCounter,
                onCheckedChange = onFloatingCounterToggle
            )
        }

        item {
            QuickSettingsTileCard()
        }

        item {
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun HistoryScreen(padding: PaddingValues, trend: List<DayCount>, limit: Int) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                "Neural Defense Log",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }

        if (trend.isNotEmpty()) {
            item {
                TrendCard(trend)
            }
            
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }

        if (trend.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No usage history yet. Start your brain health journey!", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            // Show latest days first
            items(trend.reversed()) { day ->
                DailySummaryItem(day, limit)
            }
        }

        item {
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun DailySummaryItem(day: DayCount, limit: Int) {
    val context = LocalContext.current
    val dateLabel = remember(day.dayBucket) {
        val millis = day.dayBucket * 86400000
        when {
            DateUtils.isToday(millis) -> "Today"
            DateUtils.isToday(millis + 86400000) -> "Yesterday"
            else -> DateUtils.formatDateTime(
                context,
                millis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY
            )
        }
    }

    val restraintScore = remember(day.count, limit) {
        if (limit <= 0) 0
        else ((1f - (day.count.toFloat() / (limit * 1.5f)).coerceIn(0f, 1f)) * 100).toInt()
    }

    val statusText = when {
        day.count == 0 -> "Neural Shield Maximum"
        day.count < limit * 0.5 -> "High Cognitive Focus"
        day.count < limit -> "Stable Mindset"
        day.count < limit * 1.5 -> "Brain Rot Warning"
        else -> "Excessive Stimuli Detected"
    }

    val statusColor = when {
        day.count < limit * 0.8 -> MaterialTheme.colorScheme.primary
        day.count <= limit -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.05f)
        ),
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(statusColor.copy(alpha = 0.3f), Color.Transparent)
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = statusColor
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (day.count.toFloat() / limit).coerceIn(0f, 1.2f) / 1.2f },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(6.dp)
                        .clip(CircleShape),
                    color = statusColor,
                    trackColor = statusColor.copy(alpha = 0.1f)
                )
                Text(
                    text = "${day.count} / $limit reels",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.1f))
                ) {
                    Text(
                        text = restraintScore.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = statusColor
                    )
                }
                Text(
                    "Score",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun MindfulnessTipCard() {
    val tips = listOf(
        "Take a 5-minute break every hour.",
        "Deep breathing can reduce screen fatigue.",
        "Set a daily goal for short-form content.",
        "Try a phone-free walk this evening.",
        "Use Grayscale mode to reduce screen appeal."
    )
    val tip = remember { tips.random() }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), Color.Transparent)
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = tip,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun ServiceStatusCard(isEnabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isEnabled) Icons.Default.Shield else Icons.Default.ShieldMoon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = if (isEnabled) "Neural Shield Active" else "Shield Deactivated",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = if (isEnabled) "Scanner is currently protecting your focus." else "Tap to re-enable your digital shield.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun TodaySummaryCard(count: Int) {
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
        )
    )
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient),
            contentAlignment = Alignment.Center
        ) {
            BrainVisualizer(
                usageCount = count,
                modifier = Modifier
                    .size(220.dp)
                    .alpha(0.05f)
                    .align(Alignment.CenterEnd)
                    .offset(x = 50.dp, y = 30.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp, horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 12.dp,
                    modifier = Modifier.size(190.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        BrainVisualizer(
                            usageCount = count,
                            modifier = Modifier.size(150.dp)
                        )
                    }
                }
                
                Spacer(Modifier.height(32.dp))
                
                Text(
                    "TOTAL SESSIONS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Black
                )
                
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Black
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(30.dp),
                    color = getUsageColor(count).copy(alpha = 0.2f),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        getUsageLevel(count),
                        style = MaterialTheme.typography.titleMedium,
                        color = getUsageColor(count),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun getUsageColor(count: Int) = when {
    count < 20 -> Color(0xFF4CAF50)
    count < 50 -> Color(0xFFFFC107)
    else -> Color(0xFFFF5252)
}

private fun getUsageLevel(count: Int) = when {
    count == 0 -> "Mindful usage"
    count < 10 -> "Light usage"
    count < 30 -> "Active browsing"
    count < 70 -> "High usage"
    else -> "Excessive usage"
}

@Composable
fun TrendCard(trend: List<DayCount>) {
    val context = LocalContext.current
    val last7Days = trend.takeLast(7)
    val maxCount = last7Days.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val avgCount = last7Days.asSequence().map { it.count }.average().toInt()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Weekly Activity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        ) {
                            Text(
                                "Avg: $avgCount/day",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                        ) {
                            Text(
                                "Peak: $maxCount",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(24.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp), // Increased height for labels
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                last7Days.forEach { day ->
                    val heightFactor = (day.count.toFloat() / maxCount).coerceIn(0.1f, 1f)
                    val isPeak = day.count == maxCount && maxCount > 0
                    val dayName = remember(day.dayBucket) {
                        DateUtils.formatDateTime(
                            context,
                            day.dayBucket * 86400000,
                            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
                        ).take(1)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(36.dp) // Fixed width for better spacing
                    ) {
                        // Count label
                        Text(
                            text = day.count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPeak) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isPeak) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        // The Bar - now restricted to 100dp max height to leave room for labels
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(100.dp * heightFactor)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    Brush.verticalGradient(
                                        if (isPeak) listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                        else listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                                    )
                                )
                        )
                        
                        Spacer(Modifier.height(8.dp))
                        
                        Surface(
                            shape = CircleShape,
                            color = if (isPeak) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else Color.Transparent,
                            modifier = Modifier.size(28.dp) // Slightly larger to prevent text cut
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = dayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isPeak) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun AppBreakdownItem(appCount: AppCount) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(getAppColor(appCount.packageName).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getAppIcon(appCount.packageName),
                    contentDescription = null,
                    tint = getAppColor(appCount.packageName),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                friendlyName(appCount.packageName),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                appCount.count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun FloatingCounterSettingsCard(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Heads-up Display",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Real-time usage counter overlay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                thumbContent = if (checked) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else null
            )
        }
    }
}

@Composable
fun QuickSettingsTileCard() {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Quick Pause Tile", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Add a Quick Settings tile to your notification shade to pause tracking in one tap before opening banking apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                        statusBarManager?.requestAddTileService(
                    ComponentName(context, QuickPauseTileService::class.java),
                    "ReelPulse Pause",
                    Icon.createWithResource(context, R.mipmap.ic_launcher),
                    context.mainExecutor,
                ) { _ ->
                    // Optional callback
                }
                    } else {
                        Toast.makeText(context, "Please add the tile manually from your notification shade edit panel.", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add Quick Settings Tile")
            }
        }
    }
}

private fun friendlyName(pkg: String) = when (pkg) {
    "com.instagram.android" -> "Instagram"
    "com.google.android.youtube" -> "YouTube"
    "com.snapchat.android" -> "Snapchat"
    "com.zhiliaoapp.musically" -> "TikTok"
    "com.facebook.katana" -> "Facebook"
    else -> pkg.substringAfterLast(".")
}

private fun getAppIcon(pkg: String): ImageVector = when (pkg) {
    "com.instagram.android" -> Icons.Default.CameraAlt
    "com.google.android.youtube" -> Icons.Default.PlayArrow
    "com.snapchat.android" -> Icons.Default.ChatBubble
    "com.zhiliaoapp.musically" -> Icons.Default.MusicNote
    "com.facebook.katana" -> Icons.Default.ThumbUp
    else -> Icons.Default.Apps
}

private fun getAppColor(pkg: String): Color = when (pkg) {
    "com.instagram.android" -> Color(0xFFE4405F)
    "com.google.android.youtube" -> Color(0xFFFF0000)
    "com.snapchat.android" -> Color(0xFFFFFC00)
    "com.zhiliaoapp.musically" -> Color(0xFF00F2EA)
    "com.facebook.katana" -> Color(0xFF1877F2)
    else -> Color.Gray
}
