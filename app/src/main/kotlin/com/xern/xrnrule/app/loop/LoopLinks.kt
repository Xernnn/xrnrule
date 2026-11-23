package com.xern.xrnrule.app.loop

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.xern.xrnrule.app.XrnruleApp
import com.xern.xrnrule.app.data.InstalledApps
import com.xern.xrnrule.app.ui.summarizeNames
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The link's other direction: which xrnloops habits unlock which block here, so xrnloops' habit
 * list can say "Social · 30 min" (locked), "Social · till 14:30" (open) or "Social · 12 min left"
 * (open, self-paced: I41) under a habit.
 * xrnloops keeps an identical copy of these constants
 * (`org.isoron.uhabits.xrnrule.XrnruleLinksContract`, `../xrnloops/.../xrnrule/XrnruleLinks.kt`);
 * change both together.
 *
 * - Read: `content://com.xern.xrnrule.links/links` ([LoopLinksProvider]), one row per habit item
 *   of every enabled habit block. Guarded by [LoopContract.PERMISSION], like the rest of the link.
 * - Push: [ACTION_LINKS_CHANGED] to xrnloops when a row would change ([LoopLinks.start]). No data;
 *   xrnloops reads the provider again.
 */
object LinksContract {
    const val AUTHORITY = "com.xern.xrnrule.links"
    const val PATH_LINKS = "links"
    const val ACTION_LINKS_CHANGED = "com.xern.xrnloops.action.LINKS_CHANGED"

    /** The habit's id, which is Loop's `uuid` (the habit server shares it). */
    const val COLUMN_HABIT_UUID = "habit_uuid"
    const val COLUMN_BLOCK_ID = "block_id"
    const val COLUMN_BLOCK_NAME = "block_name"

    /** What the block holds back, for a person: "Instagram + youtube.com", "Reddit + 2 more". */
    const val COLUMN_TARGETS = "targets"

    /** How long one unlock lasts. */
    const val COLUMN_UNLOCK_MINUTES = "unlock_minutes"

    /** [STATE_LOCKED] (ARMED), [STATE_READY] (EARNED) or [STATE_OPEN] (UNLOCKED). */
    const val COLUMN_STATE = "state"

    /** Wall-clock ms the open unlock ends; 0 unless [STATE_OPEN], and 0 for a self-paced one. */
    const val COLUMN_OPEN_UNTIL = "open_until"

    /** 1 when an unlock is so much use, not so much time (I41): it ends when used up or at midnight. */
    const val COLUMN_SELF_PACED = "self_paced"

    /** Ms of use left in an open self-paced unlock ([UnlockSession.leftMs]); 0 otherwise. */
    const val COLUMN_USE_LEFT_MS = "use_left_ms"

    /** 1 when this habit's item is done; the block may still want its other parts. */
    const val COLUMN_HABIT_DONE = "habit_done"

    const val STATE_LOCKED = "locked"
    const val STATE_READY = "ready"
    const val STATE_OPEN = "open"

    /** `xrnrule://open?block=<block_id>` opens xrnrule on that block's card (an unknown id: the Blocks tab). */
    const val OPEN_SCHEME = "xrnrule"
    const val OPEN_HOST = "open"
    const val OPEN_PARAM_BLOCK = "block"

    val COLUMNS = arrayOf(
        COLUMN_HABIT_UUID, COLUMN_BLOCK_ID, COLUMN_BLOCK_NAME, COLUMN_TARGETS,
        COLUMN_UNLOCK_MINUTES, COLUMN_STATE, COLUMN_OPEN_UNTIL, COLUMN_HABIT_DONE,
        COLUMN_SELF_PACED, COLUMN_USE_LEFT_MS,
    )
}

/**
 * One provider row, before app labels are looked up. Also what [LoopLinks] compares, so it leaves
 * out a self-paced unlock's use left: that changes every second the blocked app is used, and
 * xrnloops reads it again anyway when it comes to the front.
 */
data class LinkRow(
    val habitId: String,
    val blockId: String,
    val blockName: String,
    val targetPackages: List<String>,
    val targetSites: List<String>,
    val targetDesktopApps: List<String>,
    val unlockMinutes: Long,
    val state: String,
    val openUntilWall: Long,
    val habitDone: Boolean,
    val selfPaced: Boolean,
)

object LoopLinks {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Habit items of enabled habit blocks; a block in another mode never asks for a habit. */
    fun rows(world: World): List<LinkRow> = world.blocks
        .filter { it.enabled && it.mode == BlockMode.HABIT }
        .flatMap { block ->
            val session = world.sessions[block.id]?.takeIf { block.state == BlockState.UNLOCKED }
            // An open unlock keeps the kind it started as; the block's setting is for the next one.
            val selfPaced = session?.selfPaced ?: block.selfPaced
            val state = when (block.state) {
                BlockState.ARMED -> LinksContract.STATE_LOCKED
                BlockState.EARNED -> LinksContract.STATE_READY
                BlockState.UNLOCKED -> LinksContract.STATE_OPEN
            }
            world.checklist[block.id].orEmpty().mapNotNull { item ->
                val habitId = item.habitId ?: item.externalHabitId ?: return@mapNotNull null
                LinkRow(
                    habitId = habitId,
                    blockId = block.id,
                    blockName = block.name,
                    targetPackages = block.targetPackages.sorted(),
                    targetSites = block.targetSites.sorted(),
                    targetDesktopApps = block.targetDesktopApps.sorted(),
                    unlockMinutes = block.reward.unlockDurationMs / 60_000,
                    state = state,
                    // A self-paced unlock has no end time: it ends when used up or at midnight (I41).
                    openUntilWall = if (session != null && !session.selfPaced) {
                        session.startedAtWall + session.durationMs
                    } else {
                        0
                    },
                    habitDone = item.done,
                    selfPaced = selfPaced,
                )
            }
        }

    /**
     * "Instagram + youtube.com": the phone's apps by name, then sites, then laptop apps. An app
     * this phone no longer has isn't named (it read as its package id).
     */
    fun targets(context: Context, row: LinkRow): String = summarizeNames(
        row.targetPackages.map { InstalledApps.labelFor(context, it) }.filterNot { it in row.targetPackages } + row.targetSites + row.targetDesktopApps,
        empty = row.blockName,
    )

    /**
     * From [XrnruleApp.onCreate]: tells xrnloops whenever a row changes (a block opens or closes,
     * gains or loses a habit), once at start too, since xrnloops may hold rows from before.
     */
    fun start(app: XrnruleApp) {
        val store = app.container.store
        scope.launch {
            store.world.map { rows(it) }.distinctUntilChanged().collect {
                val intent = Intent(LinksContract.ACTION_LINKS_CHANGED).setPackage(LoopContract.LOOP_PACKAGE)
                app.sendBroadcast(intent, LoopContract.PERMISSION)
            }
        }
    }
}

/**
 * Read-only: xrnloops (same key, [LoopContract.PERMISSION]) reads its habits' blocks here. Only
 * ever reads [com.xern.xrnrule.app.data.WorldStore.world]'s current value, from a binder thread.
 */
class LoopLinksProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.pathSegments != listOf(LinksContract.PATH_LINKS)) return null
        val context = context ?: return null
        val cursor = MatrixCursor(LinksContract.COLUMNS)
        // Providers start before Application.onCreate; until the container exists there's nothing to say.
        val container = runCatching { (context.applicationContext as XrnruleApp).container }.getOrNull() ?: return cursor
        val world = container.store.world.value
        for (row in LoopLinks.rows(world)) {
            val useLeftMs = if (row.selfPaced && row.state == LinksContract.STATE_OPEN) {
                world.sessions[row.blockId]?.leftMs(container.clock) ?: 0
            } else {
                0
            }
            cursor.addRow(
                arrayOf<Any>(
                    row.habitId, row.blockId, row.blockName, LoopLinks.targets(context, row),
                    row.unlockMinutes, row.state, row.openUntilWall, if (row.habitDone) 1 else 0,
                    if (row.selfPaced) 1 else 0, useLeftMs,
                ),
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
