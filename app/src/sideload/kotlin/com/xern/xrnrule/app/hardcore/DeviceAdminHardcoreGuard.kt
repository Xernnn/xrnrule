package com.xern.xrnrule.app.hardcore

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.appliesHere
import com.xern.xrnrule.hardcore.HabitDeviceAdminReceiver
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Real [HardcoreGuard], compiled only into the `sideload` flavor. The password is stored as a
 * salted SHA-256 hash in a private `SharedPreferences` file — enough to stop a casual in-app
 * bypass attempt; the "Honest limits" copy in Settings already says ADB / safe mode / factory
 * reset defeat the whole app regardless (brainstorm §8), so this isn't trying to be a vault.
 */
class DeviceAdminHardcoreGuard(private val context: Context) : HardcoreGuard {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hardcore_guard", Context.MODE_PRIVATE)
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val tries = PasswordTries(prefs, SystemClock::elapsedRealtime)

    override fun isSupported() = true

    override fun isEngaged(): Boolean = hasPassword() && isDeviceAdminActive()

    override fun shouldIntercept(pkg: String?, world: World): Boolean {
        if (!isEngaged() || pkg !in GUARDED_PACKAGES) return false
        // A laptop-only block (I42) isn't held here, so it doesn't gate this phone's Settings.
        return world.blocks.any {
            it.enforcementTier == EnforcementTier.HARDCORE && it.state != BlockState.UNLOCKED && world.appliesHere(it)
        }
    }

    override fun hasPassword(): Boolean = prefs.contains(KEY_HASH)

    override fun changePassword(current: String, new: String): Boolean {
        if (!verifyPassword(current)) return false
        setPassword(new)
        return true
    }

    override fun setPassword(password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit {
            putString(KEY_SALT, salt.toHex())
            putString(KEY_HASH, hash(password, salt))
        }
    }

    override fun verifyPassword(input: String): Boolean {
        val saltHex = prefs.getString(KEY_SALT, null) ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        return tries.attempt { hash(input, saltHex.hexToBytes()) == expected }
    }

    override fun waitMs(): Long = tries.waitMs()

    override fun isDeviceAdminActive(): Boolean =
        dpm?.isAdminActive(HabitDeviceAdminReceiver.component(context)) == true

    override fun requestDeviceAdmin(activity: Activity) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, HabitDeviceAdminReceiver.component(activity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "xrnrule uses this to stop itself being uninstalled while a hardcore block is active.",
            )
        }
        activity.startActivity(intent)
    }

    private fun hash(password: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(password.toByteArray(Charsets.UTF_8)).toHex()
    }

    companion object {
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"

        /**
         * System Settings, plus the OEM "security" apps that can also force-stop xrnrule or
         * switch off its permissions (MIUI's Security app is the one on the test phone).
         */
        private val GUARDED_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.huawei.systemmanager",
            "com.iqoo.secure",
            "com.vivo.permissionmanager",
            "com.oneplus.security",
            "com.samsung.android.lool",
            "com.samsung.android.sm.devicesecurity",
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
