package com.bytefoss.peekwell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts motion watching when the TV boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) MotionService.syncService(ctx, false)
    }
}
