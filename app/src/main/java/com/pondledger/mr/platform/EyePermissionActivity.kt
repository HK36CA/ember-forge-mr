package com.pondledger.mr.platform

import android.app.Activity
import android.os.Bundle
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings

const val EYE_PERMISSION = "com.picovr.permission.EYE_TRACKING"

/** The spatial compositor may supply an application Context, so own the Android permission dialog here. */
class EyePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(EYE_PERMISSION) == PackageManager.PERMISSION_GRANTED) finish()
        else if (savedInstanceState == null) {
            val before = getPreferences(MODE_PRIVATE).getBoolean("denied",false)
            if (before && !shouldShowRequestPermissionRationale(EYE_PERMISSION)) {
                val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                if (runCatching { startActivity(details) }.isSuccess) { finish(); return }
            }
            requestPermissions(arrayOf(EYE_PERMISSION), 71)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71) {
            getPreferences(MODE_PRIVATE).edit()
                .putBoolean("denied", grantResults.firstOrNull()==PackageManager.PERMISSION_DENIED).apply()
            finish()
        }
    }
}
