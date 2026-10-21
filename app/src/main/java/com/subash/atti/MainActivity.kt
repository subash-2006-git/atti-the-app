package com.subash.atti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.subash.atti.ui.theme.AttiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AttiTheme {
                AttiApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as AttiApplication).nearby.resume()
    }

    override fun onStop() {
        super.onStop()
        (application as AttiApplication).nearby.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Closing the app ends any chat; rotating the screen does not.
        if (isFinishing) (application as AttiApplication).nearby.stop()
    }
}
