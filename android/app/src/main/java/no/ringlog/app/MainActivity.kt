package no.ringlog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import no.ringlog.app.data.local.TokenStore
import no.ringlog.app.data.repository.AuthRepository
import no.ringlog.app.ui.auth.LoginScreen
import no.ringlog.app.ui.birds.BirdDetailScreen
import no.ringlog.app.ui.flocks.FlockDetailScreen
import no.ringlog.app.ui.flocks.FlockListScreen
import no.ringlog.app.ui.flocks.FlockReportScreen
import no.ringlog.app.ui.hatches.HatchDetailScreen
import no.ringlog.app.ui.hatches.HatchListScreen
import no.ringlog.app.ui.log.DailyLogScreen
import no.ringlog.app.ui.theme.RingLogTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var auth: AuthRepository
    @Inject lateinit var tokenStore: TokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RingLogTheme {
                RingLogNavHost(auth, tokenStore)
            }
        }
    }
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val bottomNavItems = listOf(
    NavItem("flocks",  "Flocks",  Icons.Default.Home),
    NavItem("log",     "Log",     Icons.Default.DateRange),
    NavItem("hatches", "Hatches", Icons.Default.Star),
    NavItem("account", "Account", Icons.Default.Person),
)

@Composable
private fun RingLogNavHost(auth: AuthRepository, tokenStore: TokenStore) {
    val navController = rememberNavController()
    var loggedIn by remember { mutableStateOf(auth.isLoggedIn) }

    if (!loggedIn) {
        LoginScreen(onLoggedIn = { loggedIn = true })
        return
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevel = bottomNavItems.map { it.route }
    val showBottomBar = topLevel.any { currentRoute == it }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController,
            startDestination = "flocks",
            modifier = Modifier.padding(padding),
        ) {
            composable("flocks") {
                FlockListScreen(onFlockClick = { navController.navigate("flock/$it") })
            }
            composable("flock/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                val id = it.arguments!!.getInt("id")
                FlockDetailScreen(
                    flockId = id,
                    onBack = { navController.popBackStack() },
                    onBirdClick = { birdId -> navController.navigate("bird/$birdId") },
                    onReportClick = { fId, name ->
                        navController.navigate("report/$fId/${java.net.URLEncoder.encode(name, "UTF-8")}")
                    },
                )
            }
            composable(
                "report/{flockId}/{flockName}",
                arguments = listOf(
                    navArgument("flockId")   { type = NavType.IntType },
                    navArgument("flockName") { type = NavType.StringType },
                )
            ) {
                FlockReportScreen(
                    flockId   = it.arguments!!.getInt("flockId"),
                    flockName = it.arguments!!.getString("flockName") ?: "",
                    onBack    = { navController.popBackStack() },
                )
            }
            composable("bird/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                val id = it.arguments!!.getInt("id")
                BirdDetailScreen(
                    birdId = id,
                    onBack = { navController.popBackStack() },
                    tokenStore = tokenStore,
                )
            }
            composable("log")     { DailyLogScreen() }
            composable("hatches") {
                HatchListScreen(onHatchClick = { navController.navigate("hatch/$it") })
            }
            composable("hatch/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                val id = it.arguments!!.getInt("id")
                HatchDetailScreen(hatchId = id, onBack = { navController.popBackStack() })
            }
            composable("account") {
                val scope = rememberCoroutineScope()
                AccountScreen(username = tokenStore.username ?: "", onLogout = {
                    scope.launch { auth.logout() }
                    loggedIn = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountScreen(username: String, onLogout: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Log out?") },
            text  = { Text("You will need to log in again to use the app.") },
            confirmButton = { TextButton(onClick = { showDialog = false; onLogout() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Account") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text("Logged in as $username", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { showDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Log out") }
        }
    }
}
