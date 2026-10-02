package com.latenighthack.basekit.viewmodel.compose

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.robolectric.Shadows.shadowOf

/** Register the host only in Robolectric, for both debug and release tests. */
class ComposeTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        shadowOf(packageManager).addActivityIfNotPresent(ComponentName(this, ComponentActivity::class.java))
    }
}
