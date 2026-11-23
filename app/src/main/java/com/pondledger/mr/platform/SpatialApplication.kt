package com.pondledger.mr.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.pondledger.mr.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
