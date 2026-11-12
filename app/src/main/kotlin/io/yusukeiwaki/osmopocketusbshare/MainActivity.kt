package io.yusukeiwaki.osmopocketusbshare

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.yusukeiwaki.osmopocketusbshare.theme.OsmoPocketTheme
import io.yusukeiwaki.osmopocketusbshare.ui.AppViewModel
import io.yusukeiwaki.osmopocketusbshare.ui.BrowserScreen
import io.yusukeiwaki.osmopocketusbshare.ui.DetailScreen
import io.yusukeiwaki.osmopocketusbshare.ui.ExportsScreen

object Routes {
    const val BROWSER = "browser"
    const val DETAIL = "detail/{index}"
    const val EXPORTS = "exports"
    fun detail(index: Int) = "detail/$index"
}

class MainActivity : ComponentActivity() {

    /** Incremented whenever a notification asks to land on the exports screen. */
    private var openExportsSignal by androidx.compose.runtime.mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMediaPermissionsIfNeeded()
        handleDeepLink(intent)
        setContent {
            OsmoPocketTheme {
                OsmoPocketApp(openExportsSignal = openExportsSignal)
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.getBooleanExtra(io.yusukeiwaki.osmopocketusbshare.export.ExportService.EXTRA_OPEN_EXPORTS, false) == true) {
            // Consume the extra so later resumes don't navigate again.
            intent.removeExtra(io.yusukeiwaki.osmopocketusbshare.export.ExportService.EXTRA_OPEN_EXPORTS)
            openExportsSignal++
        }
    }

    private fun requestMediaPermissionsIfNeeded() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_MEDIA_IMAGES)
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
            } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1001)
    }
}

@Composable
fun OsmoPocketApp(viewModel: AppViewModel = viewModel(), openExportsSignal: Int = 0) {
    val navController: NavHostController = rememberNavController()
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val jobs by viewModel.exportJobs.collectAsState()
    val batches by viewModel.exportBatches.collectAsState()
    val latestBatch = batches.firstOrNull()

    // Notification taps land directly on the exports screen.
    LaunchedEffect(openExportsSignal) {
        if (openExportsSignal > 0) {
            navController.navigate(Routes.EXPORTS) { launchSingleTop = true }
        }
    }

    // Refresh automatically when a USB device is attached/detached.
    // Note: one filter with a data scheme never matches USB_DEVICE_* broadcasts (they carry
    // no data URI), which previously left a stale list on screen after the cable was pulled.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                viewModel.refresh()
            }
        }
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, usbFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, receiver, mediaFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Some cameras mount as a SAF-only volume: Android emits no MEDIA_MOUNTED broadcast
    // for them. The provider notifies its observers when the volume becomes available.
    DisposableEffect(context, state.savedFolderUri) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                viewModel.refresh()
            }
        }
        state.savedFolderUri?.let { savedUri ->
            // Observe the granted tree, rather than requesting access to all provider roots.
            runCatching { resolver.registerContentObserver(Uri.parse(savedUri), true, observer) }
        }
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    // Rescan on resume only when the connection state changed (rotation alone must not reload).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refreshOnResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) viewModel.onSafTreeGranted(uri)
    }

    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                state = state,
                onRefresh = { viewModel.refresh() },
                onToggleLayout = { viewModel.toggleLayout() },
                onItemClick = { item ->
                    val index = state.items.indexOfFirst { it.id == item.id }
                    if (index >= 0) navController.navigate(Routes.detail(index))
                },
                onItemLongClick = { viewModel.startSelection(it) },
                onToggleSelection = { viewModel.toggleSelection(it) },
                onClearSelection = { viewModel.clearSelection() },
                onSelectAll = { viewModel.selectAll() },
                onExportSelected = {
                    viewModel.exportSelected()
                    navController.navigate(Routes.EXPORTS)
                },
                onOpenExports = { navController.navigate(Routes.EXPORTS) },
                onPickFolder = { folderPicker.launch(viewModel.openTreeIntent()) },
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("index") { type = NavType.IntType }),
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            DetailScreen(
                items = state.items,
                initialIndex = index,
                onBack = { navController.popBackStack() },
                onExport = { item ->
                    viewModel.exportItems(listOf(item))
                    navController.navigate(Routes.EXPORTS)
                },
            )
        }
        composable(Routes.EXPORTS) {
            ExportsScreen(
                jobs = jobs,
                batch = latestBatch,
                onBack = { navController.popBackStack() },
                onCancel = { viewModel.cancelExport(it) },
                onClearHistory = { viewModel.clearExportHistory() },
            )
        }
    }
    // No refresh here: ViewModel.init loads once, and the ViewModel survives rotation.
    // Re-running refresh on every composition/Activity recreation caused the reload spinner.
}
