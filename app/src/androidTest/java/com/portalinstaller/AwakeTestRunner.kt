/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.test.runner.AndroidJUnitRunner

/** Fixture activities need the same visible-window wake protection as MainActivity. */
class AwakeTestRunner : AndroidJUnitRunner() {
    private var targetApplication: Application? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) = keepAwake(activity)
        override fun onActivityResumed(activity: Activity) = keepAwake(activity)
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun keepAwake(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        targetApplication = targetContext.applicationContext as Application
        targetApplication?.registerActivityLifecycleCallbacks(callbacks)
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        targetApplication?.unregisterActivityLifecycleCallbacks(callbacks)
        targetApplication = null
        super.finish(resultCode, results)
    }
}
