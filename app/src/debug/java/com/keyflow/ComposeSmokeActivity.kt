package com.keyflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Smoke de fundação (T1): prova que o toolchain Compose compila e renderiza numa Activity.
 * Existe só no build de debug; será substituída pela GalleryActivity (T7) e pela ConfigScreen (T10).
 */
class ComposeSmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ComposeSmoke()
                }
            }
        }
    }
}

@Composable
fun ComposeSmoke() {
    Text(text = "KeyFlow Compose OK")
}
