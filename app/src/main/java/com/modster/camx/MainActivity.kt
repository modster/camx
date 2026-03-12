package com.modster.camx

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.modster.camx.ui.theme.CamXTheme

/**
 * The sole Activity for CamX.
 *
 * Responsibilities:
 *  - Edge-to-edge display
 *  - Camera-permission gate (via Accompanist)
 *  - Instantiate [WebGpuProcessor] and wire it to the [CameraViewModel]
 *  - Host [CameraScreen]
 */
class MainActivity : ComponentActivity() {

    private val cameraViewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CamXTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CamXApp(cameraViewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CamXApp(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    // Initialise the WebGPU processor once; destroy it when the composable leaves.
    val webGpuProcessor = remember { WebGpuProcessor(context) }
    DisposableEffect(Unit) {
        webGpuProcessor.initialize { /* GPU adapter ready */ }
        onDispose { webGpuProcessor.destroy() }
    }

    // Whenever a new photo is captured, submit it to the WebGPU color inverter.
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.capturedBitmap) {
        val bmp = state.capturedBitmap ?: return@LaunchedEffect
        webGpuProcessor.processImage(bmp) { processed ->
            if (processed != null) viewModel.setProcessedBitmap(processed)
        }
    }

    when {
        cameraPermission.status.isGranted -> {
            CameraScreen(viewModel = viewModel)
        }

        cameraPermission.status.shouldShowRationale -> {
            PermissionRationaleScreen(onRequest = { cameraPermission.launchPermissionRequest() })
        }

        else -> {
            // First launch – immediately request the permission
            LaunchedEffect(Unit) { cameraPermission.launchPermissionRequest() }
            PermissionRationaleScreen(onRequest = { cameraPermission.launchPermissionRequest() })
        }
    }
}

@Composable
private fun PermissionRationaleScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Camera permission is required to use CamX.",
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequest) {
                Text("Grant Permission")
            }
        }
    }
}
