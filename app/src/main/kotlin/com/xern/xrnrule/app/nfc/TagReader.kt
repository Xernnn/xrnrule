package com.xern.xrnrule.app.nfc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import com.xern.xrnrule.app.XrnruleApp
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.Tags
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NFC tags that unlock blocks (contract I43). A tag is read two ways:
 * - anywhere, screen on: Android hands a tag no other app claims to [TagActivity], which ticks
 *   and is gone at once, so you stay in the app you were in (a blocked app's shield then shows
 *   "Open");
 * - with xrnrule in front: reader mode (see [readerWanted]), which also catches tags that carry a
 *   link or other data (Android would open those elsewhere) and is how a new tag is set up.
 */
object TagReader {
    private val main = Handler(Looper.getMainLooper())

    /** Set while the form's "Scan a tag" is up: reads go to it instead of ticking. */
    private val _registering = MutableStateFlow<((String) -> Unit)?>(null)
    val registering: StateFlow<((String) -> Unit)?> = _registering.asStateFlow()

    fun startRegistering(onRead: (String) -> Unit) {
        _registering.value = onRead
    }

    fun stopRegistering() {
        _registering.value = null
    }

    fun adapter(context: Context): NfcAdapter? = NfcAdapter.getDefaultAdapter(context)
    fun hasNfc(context: Context) = adapter(context) != null
    fun isOn(context: Context) = adapter(context)?.isEnabled == true
    fun settingsIntent() = Intent(Settings.ACTION_NFC_SETTINGS)

    /** Whether some block wants a tag, so xrnrule in front should read them. */
    fun wanted(world: World) = world.checklist.values.any { items -> items.any { it.tag != null } }

    /** Reader mode on [activity] while it's in front and [on]; off otherwise. */
    fun readerMode(activity: Activity, on: Boolean) {
        val nfc = adapter(activity) ?: return
        runCatching {
            if (on) {
                val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                nfc.enableReaderMode(activity, { tag -> main.post { read(activity, tag) } }, flags, Bundle())
            } else {
                nfc.disableReaderMode(activity)
            }
        }
    }

    /** A tag read, by either path. */
    fun read(context: Context, tag: Tag) {
        val id = Tags.id(tag.id ?: return)
        _registering.value?.let { onRead ->
            onRead(id)
            return
        }
        scanned(context, (context.applicationContext as XrnruleApp).container.store, id)
    }

    private fun scanned(context: Context, store: WorldStore, id: String) {
        val world = store.world.value
        val items = world.checklist.values.flatten().filter { it.tag == id }
        if (items.isEmpty()) {
            Toast.makeText(context, "xrnrule doesn't know this tag", Toast.LENGTH_SHORT).show()
            return
        }
        store.dispatch(BlockEvent.TagScanned(id))
        val names = items.mapNotNull { item -> world.block(item.blockId)?.name }.distinct()
        Toast.makeText(context, "Scanned ${items.first().text}: ${names.joinToString(", ")}", Toast.LENGTH_SHORT).show()
    }
}

/** Android's NFC dispatch lands here (TECH_DISCOVERED): read, tick, and go. No window of its own. */
class TagActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val tag = intent?.let { IntentCompat.getParcelableExtra(it, NfcAdapter.EXTRA_TAG, Tag::class.java) } ?: return
        TagReader.read(this, tag)
    }
}
