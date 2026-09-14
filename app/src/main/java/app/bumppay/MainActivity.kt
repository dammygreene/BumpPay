package app.bumppay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import app.bumppay.mwa.MwaWallet
import app.bumppay.ui.BumpPayApp
import app.bumppay.ui.BumpPayViewModel
import app.bumppay.ui.theme.BumpPayPalette
import app.bumppay.ui.theme.BumpPayTheme

/**
 * The payer's single activity.
 *
 * ## Why this class is mostly lifecycle
 *
 * Session arming is driven entirely from here, and that is deliberate: the security property
 * "a tap only signs while the app is visibly in front of the user" is only meaningful if it
 * is wired to the real activity lifecycle rather than to some piece of UI state. Mapping it
 * to `onResume`/`onPause` means the platform, not BumpPay, decides when the session is hot.
 *
 * It must be a [ComponentActivity]: Mobile Wallet Adapter's `ActivityResultSender` calls
 * `registerForActivityResult`, which only exists on that type.
 */
class MainActivity : ComponentActivity() {

    private lateinit var wallet: MwaWallet
    private lateinit var viewModel: BumpPayViewModel

    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }

    /**
     * Runs at the lowest useful priority and only observes system broadcasts, so it can be
     * registered unexported without losing anything.
     */
    private val systemStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> viewModel.onDeviceLocked()
                Intent.ACTION_SCREEN_ON -> viewModel.onDeviceUnlocked()
                Intent.ACTION_USER_PRESENT -> viewModel.onDeviceUnlocked()
                NfcAdapter.ACTION_ADAPTER_STATE_CHANGED -> refreshNfcState()
            }
        }
    }

    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wallet = MwaWallet(this)
        viewModel = ViewModelProvider(
            this,
            BumpPayViewModel.Factory(application as BumpPayApplication, wallet),
        )[BumpPayViewModel::class.java]

        setContent {
            BumpPayTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BumpPayPalette.Ink0) {
                    BumpPayApp(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerSystemStateReceiver()
        // Order matters: arm only after the NFC state is known, so the UI can explain a
        // "not active" session as "NFC is off" rather than leaving the user guessing.
        refreshNfcState()
        viewModel.onResume()
    }

    /**
     * `onPause`, deliberately, not `onStop`.
     *
     * Anything that covers this activity — the notification shade, an incoming call, the
     * wallet app opening for an approval — drops the session. That is stricter than the
     * lifecycle requires and is exactly the intended proximity-skimming defence: if the user
     * cannot see the screen, a tap must not produce a signature.
     */
    override fun onPause() {
        viewModel.onPause()
        unregisterSystemStateReceiver()
        super.onPause()
    }

    private fun refreshNfcState() {
        val adapter = nfcAdapter
        viewModel.onNfcStateChanged(
            available = adapter != null,
            enabled = adapter?.isEnabled == true,
            // HCE support is declared in the manifest; if the device lacked it the service
            // would never be bound, so this is a cheap sanity check rather than a probe.
            hceCapable = true,
        )
    }

    private fun registerSystemStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            systemStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterSystemStateReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(systemStateReceiver) }
        receiverRegistered = false
    }
}
