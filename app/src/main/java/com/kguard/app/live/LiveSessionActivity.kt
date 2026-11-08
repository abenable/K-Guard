package com.kguard.app.live

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible step that starts K-Guard Live: shows Android's screen-sharing prompt, hands the result to
 * [LiveWatchService], and closes, leaving the child on the screen they were on.
 */
// A plain ComponentActivity (no Fragments), so the Fragment-version check does not apply.
@android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
class LiveSessionActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            LiveWatchService.start(this, result.resultCode, data)
            Toast.makeText(this, "K-Guard Live is on", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "K-Guard Live needs screen sharing to start.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LiveWatchService.running) {
            Toast.makeText(this, "K-Guard Live is already on", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (savedInstanceState == null) {
            consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }
}
