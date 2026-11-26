package com.xern.xrnrule.app.hardcore

import android.app.Activity
import com.xern.xrnrule.domain.World

/**
 * Anti-bypass surface. The real implementation lives in the `:hardcore`
 * module and is only linked into the `sideload` build flavor; `:app`'s main source set never
 * references `:hardcore` directly so the `play` flavor can exclude it entirely. The
 * flavor-specific factory (`HardcoreGuardFactory.kt` under `src/sideload` / `src/play`) wires
 * the real implementation — or [NoopHardcoreGuard] — in at compile time.
 */
interface HardcoreGuard {
    /** False in the `play` flavor, where `:hardcore` isn't linked in at all. */
    fun isSupported(): Boolean

    /** True once a password is set *and* device admin is active — i.e. the guard does anything. */
    fun isEngaged(): Boolean

    /** True if [pkg] is system Settings (or an OEM security app) and some HARDCORE block in [world] is still armed. */
    fun shouldIntercept(pkg: String?, world: World): Boolean

    fun hasPassword(): Boolean

    /** Sets the first password; [changePassword] replaces it later. */
    fun setPassword(password: String)

    /** Whether [input] is the password; false without checking while a wait is due ([waitMs]). */
    fun verifyPassword(input: String): Boolean

    /** How long until the next password try is taken, after too many wrong ones ([PasswordTries]). */
    fun waitMs(): Long = 0

    /** Replaces the password if [current] is right; there is deliberately no way to remove it. */
    fun changePassword(current: String, new: String): Boolean

    fun isDeviceAdminActive(): Boolean
    fun requestDeviceAdmin(activity: Activity)
}

object NoopHardcoreGuard : HardcoreGuard {
    override fun isSupported() = false
    override fun isEngaged() = false
    override fun shouldIntercept(pkg: String?, world: World) = false
    override fun hasPassword() = false
    override fun setPassword(password: String) = Unit
    override fun verifyPassword(input: String) = false
    override fun changePassword(current: String, new: String) = false
    override fun isDeviceAdminActive() = false
    override fun requestDeviceAdmin(activity: Activity) = Unit
}
