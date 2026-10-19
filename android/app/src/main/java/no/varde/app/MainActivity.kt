package no.varde.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import no.varde.app.inbox.Inbox
import no.varde.app.notifications.NotificationDestination
import no.varde.app.notifications.NotificationIntents
import no.varde.app.notifications.NotificationRuntime

class MainActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        ViewModelProvider(this)[InboxViewModel::class.java].recoverAlerts()
    }

    private fun notificationPermission() {
        lifecycleScope.launch {
            val model = ViewModelProvider(this@MainActivity)[InboxViewModel::class.java]
            try {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && model.reservePermissionPrompt()) {
                    permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: no.varde.app.inbox.InboxFailure) { }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[InboxViewModel::class.java]
        if (savedInstanceState == null) model.openNotification(intent)
        setContent {
            val notification by model.notification.collectAsStateWithLifecycle()
            val recoveryProblems by model.recoveryProblems.collectAsStateWithLifecycle()
            val notificationControls by model.notificationControls.collectAsStateWithLifecycle()
            VardeApp(model.inbox, notification, model::notificationHandled,
                notificationTools = { LocalNotificationTools(model.inbox) },
                recoveryProblems = recoveryProblems, onRecoveryRetry = model::retryRecovery,
                notificationControls = notificationControls, onNotificationToggle = model::setNotificationsEnabled,
                onNotificationPermission = ::notificationPermission, onNotificationRetry = model::retryRecovery)
        }
    }

    override fun onStart() {
        super.onStart()
        ViewModelProvider(this)[InboxViewModel::class.java].recoverAlerts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ViewModelProvider(this)[InboxViewModel::class.java].openNotification(intent)
    }
}

class InboxViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = NotificationRuntime.get(application)
    val inbox = Inbox(viewModelScope, runtime.settings, runtime.backend)
    private val mutableNotification = MutableStateFlow<NotificationDestination?>(null)
    val notification = mutableNotification.asStateFlow()
    val recoveryProblems = runtime.coordinator.recoveryProblems
    val notificationControls = runtime.coordinator.notificationControls

    init {
        viewModelScope.launch {
            inbox.state.map { it.initialized to it.connections }.distinctUntilChanged().collect { (initialized, _) ->
                if (initialized) runtime.launch { runtime.coordinator.synchronize() }
            }
        }
    }

    fun openNotification(intent: Intent) {
        val target = NotificationIntents.openTarget(intent) ?: return
        viewModelScope.launch {
            inbox.state.first { it.initialized }
            try {
                mutableNotification.value = runtime.coordinator.resolveTap(target)
            } catch (_: no.varde.app.inbox.InboxFailure) { }
        }
    }

    fun recoverAlerts() { runtime.launch { runtime.refreshNotifications() } }
    fun retryRecovery(connectionId: String) { runtime.launch { runtime.refreshNotifications(connectionId) } }
    fun setNotificationsEnabled(connectionId: String, enabled: Boolean) { runtime.setNotificationsEnabled(connectionId, enabled) }
    suspend fun reservePermissionPrompt() = runtime.coordinator.reservePermissionPrompt()

    fun notificationHandled() { mutableNotification.value = null }
}
