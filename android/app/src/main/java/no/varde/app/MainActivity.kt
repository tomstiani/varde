package no.varde.app

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import no.varde.app.inbox.HttpBackend
import no.varde.app.inbox.Inbox
import no.varde.app.inbox.SecureConnectionSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[InboxViewModel::class.java]
        setContent { VardeApp(model.inbox) }
    }
}

class InboxViewModel(application: Application) : AndroidViewModel(application) {
    val inbox = Inbox(viewModelScope, SecureConnectionSettings(application), HttpBackend())
}
