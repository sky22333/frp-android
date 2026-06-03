package com.sky22333.frpandroid

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sky22333.frpandroid.core.data.AppGraph
import com.sky22333.frpandroid.core.data.FrpSettings
import com.sky22333.frpandroid.core.frp.LanguageMode
import com.sky22333.frpandroid.core.runtime.FrpForegroundService
import com.sky22333.frpandroid.core.ui.FrpAndroidTheme
import com.sky22333.frpandroid.feature.dashboard.DashboardScreen
import com.sky22333.frpandroid.feature.editor.EditorScreen
import com.sky22333.frpandroid.feature.logs.LogsScreen
import com.sky22333.frpandroid.feature.profiles.ProfilesScreen
import com.sky22333.frpandroid.feature.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    private val startDestination = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startDestination.value = intent?.getStringExtra(FrpForegroundService.EXTRA_START_DESTINATION)
        val repository = AppGraph.repository(this)
        setContent {
            val settings by repository.settings.collectAsStateWithLifecycle(initialValue = FrpSettings())
            ApplyLanguage(settings.languageMode)
            FrpAndroidTheme(themeMode = settings.themeMode) {
                ApplySystemBars()
                FrpApp(startDestination = startDestination.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startDestination.value = intent.getStringExtra(FrpForegroundService.EXTRA_START_DESTINATION)
    }
}

@Composable
private fun ApplySystemBars() {
    val view = LocalView.current
    val background = MaterialTheme.colorScheme.background
    val backgroundArgb = background.toArgb()
    val darkMode = background.luminance() < 0.5f
    SideEffect {
        if (!view.isInEditMode) {
            view.context.findActivity()?.let { activity ->
                activity.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(backgroundArgb, backgroundArgb) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(backgroundArgb, backgroundArgb) { darkMode },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    activity.window.isNavigationBarContrastEnforced = false
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun ApplyLanguage(languageMode: LanguageMode) {
    LaunchedEffect(languageMode) {
        val tags = when (languageMode) {
            LanguageMode.System -> ""
            LanguageMode.Chinese -> "zh-CN"
            LanguageMode.English -> "en"
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrpApp(startDestination: String?) {
    val navController = rememberNavController()
    LaunchedEffect(startDestination) {
        if (startDestination == FrpForegroundService.DESTINATION_LOGS) {
            navController.navigate(Screen.Logs.route) {
                launchSingleTop = true
            }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = { BottomNavigation(navController) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (startDestination == FrpForegroundService.DESTINATION_LOGS) {
                Screen.Logs.route
            } else {
                Screen.Dashboard.route
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            frpGraph(navController)
        }
    }
}

private fun NavGraphBuilder.frpGraph(navController: NavHostController) {
    composable(Screen.Dashboard.route) {
        DashboardScreen()
    }
    composable(Screen.Profiles.route) {
        ProfilesScreen(onEditProfile = { id -> navController.navigate("editor/$id") })
    }
    composable(Screen.Logs.route) {
        LogsScreen()
    }
    composable(Screen.Settings.route) {
        SettingsScreen()
    }
    composable("editor/{profileId}") { backStackEntry ->
        EditorScreen(profileId = backStackEntry.arguments?.getString("profileId").orEmpty())
    }
}

@Composable
private fun BottomNavigation(navController: NavHostController) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Screen.topLevel.forEach { screen ->
            NavigationBarItem(
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(Screen.Dashboard.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(screen.icon, contentDescription = stringResource(screen.labelRes)) },
                label = { Text(stringResource(screen.labelRes)) },
            )
        }
    }
}

private sealed class Screen(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    data object Dashboard : Screen("dashboard", R.string.nav_dashboard, Icons.Rounded.Dashboard)
    data object Profiles : Screen("profiles", R.string.nav_profiles, Icons.Rounded.Tune)
    data object Logs : Screen("logs", R.string.nav_logs, Icons.AutoMirrored.Rounded.Article)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Rounded.Settings)

    companion object {
        val topLevel = listOf(Dashboard, Profiles, Logs, Settings)
    }
}
