package com.focussounds

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner

/** Points the app at "_test" storage before it starts, so tests never read or write real history. */
class FocusTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        FocusApp.storageSuffix = "_test"
        super.callApplicationOnCreate(app)
    }
}
