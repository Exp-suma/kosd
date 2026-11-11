package app.narabuspulse.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.narabuspulse.core.Session
import app.narabuspulse.core.str
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHostState

/** 画面遷移（Web のパス "/board/123" のまま指定できる）。 */
class Nav(val controller: NavHostController) {
    fun go(webPath: String) {
        val route = webPathToRoute(webPath)
        controller.navigate(route) { launchSingleTop = true }
    }

    /** ドロワーからの移動：履歴をホームまで戻してから開く。 */
    fun top(webPath: String) {
        val route = webPathToRoute(webPath)
        controller.navigate(route) {
            popUpTo("home") { inclusive = route == "home" }
            launchSingleTop = true
        }
    }

    fun back() { if (!controller.popBackStack()) Unit }
}

private fun NavBackStackEntry.webPath(): String {
    val pattern = destination.route ?: return "/"
    if (pattern == "home") return "/"
    var p = pattern
    Regex("\\{(\\w+)\\}").findAll(pattern).forEach { m ->
        p = p.replace(m.value, arguments?.getString(m.groupValues[1]).orEmpty())
    }
    return "/$p"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(onLogout: () -> Unit) {
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val entry by controller.currentBackStackEntryAsState()
    val path = entry?.webPath() ?: "/"
    val screen = SCREENS.firstOrNull { it.route == entry?.destination?.route }
    val isTop = entry?.destination?.route == "home" || NAV_GROUPS.any { g -> g.items.any { webPathToRoute(it.path) == entry?.destination?.route } }

    val devRoute = DevRoute.pending
    androidx.compose.runtime.LaunchedEffect(devRoute) {
        if (devRoute != null) { nav.go(devRoute); DevRoute.pending = null }
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalNav provides nav) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                DrawerContent(path, onSelect = { item ->
                    scope.launch { drawer.close() }
                    nav.top(item.path)
                }, onLogout = onLogout)
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(screen?.title ?: "奈良バスパルス", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                currentNav(path)?.first?.label?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        },
                        navigationIcon = {
                            if (isTop) {
                                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "メニュー") }
                            } else {
                                IconButton(onClick = { controller.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                NavHost(controller, startDestination = "home", modifier = Modifier.padding(padding).fillMaxSize()) {
                    SCREENS.forEach { def ->
                        composable(def.route) { backStack ->
                            ScreenContent(def, backStack.arguments?.let { b -> b.keySet().associateWith { b.getString(it).orEmpty() } } ?: emptyMap())
                        }
                    }
                }
            }
        }
    }
}

val LocalNav = androidx.compose.runtime.staticCompositionLocalOf<Nav> { error("Nav が未設定です") }

/** 開発用：adb から開く画面を指定する（デバッグ版のみ MainActivity が設定）。 */
object DevRoute {
    var pending by androidx.compose.runtime.mutableStateOf<String?>(null)
}

@Composable
private fun DrawerContent(path: String, onSelect: (NavItem) -> Unit, onLogout: () -> Unit) {
    val user by Session.user.collectAsState()
    val closed = remember { mutableStateMapOf<String, Boolean>() }
    ModalDrawerSheet(Modifier.width(300.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DirectionsBus, null, tint = Brand.Orange, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("バスロケ監視", fontWeight = FontWeight.Bold)
                    Text(user.str("full_name") ?: user.str("email") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            NAV_GROUPS.forEach { group ->
                val open = closed[group.label] != true
                Text(
                    group.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                        .then(Modifier),
                )
                if (open) group.items.filter { it.path != "/admin/board" || Session.isAdmin }.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label, fontSize = 13.sp) },
                        icon = { Icon(item.icon, null, Modifier.size(20.dp)) },
                        selected = item.isActive(path),
                        onClick = { onSelect(item) },
                        modifier = Modifier.padding(horizontal = 8.dp).height(42.dp),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                label = { Text("ログアウト", fontSize = 13.sp) },
                icon = { Icon(Icons.Outlined.Logout, null, Modifier.size(20.dp)) },
                selected = false,
                onClick = onLogout,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}
