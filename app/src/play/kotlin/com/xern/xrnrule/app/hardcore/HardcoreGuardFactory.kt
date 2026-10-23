package com.xern.xrnrule.app.hardcore

import android.content.Context

/** `play` flavor: `:hardcore` isn't linked in, so every block stays SOFT. */
fun createHardcoreGuard(context: Context): HardcoreGuard = NoopHardcoreGuard
