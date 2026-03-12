package com.modster.camx

import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Main camera screen composable.
 *
 * Shows a full-screen camera preview with an overlay of controls:
 *  - Capture button
 *  - Front / back lens toggle
 *  - Auto / manual focus toggle (tap preview to focus when manual)
 *  - Long-exposure toggle with an exposure-time slider
 *
 * After a photo is captured the user sees a result sheet where they can:
 *  - View the original capture
 *  - Toggle the WebGPU color-inverted version
 *  - Dismiss back to the live preview
 */
@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Remember the PreviewView so we can reference it for tap-to-focus
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Bind the camera when the composable enters the composition, or when
    // lens / exposure settings change.
    LaunchedEffect(state.isBackCamera, state.isLongExposure, state.exposureTimeMs) {
        viewModel.bindCamera(context, previewView, lifecycleOwner)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Live camera preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        ) { view ->
            // Forward tap events to the ViewModel for manual tap-to-focus
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    viewModel.tapToFocus(event.x, event.y, previewView)
                }
                false
            }
        }

        // Top bar: exposure controls
        TopControls(
            state = state,
            onLongExposureToggle = { viewModel.setLongExposure(it) },
            onExposureChange = { viewModel.setExposureTimeMs(it) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        )

        // Bottom bar: capture + lens + focus
        BottomControls(
            state = state,
            onCapture = { viewModel.capturePhoto { /* bitmap is stored in the VM */ } },
            onSwitchLens = { viewModel.switchCamera(context, previewView, lifecycleOwner) },
            onToggleFocus = { viewModel.setAutoFocus(!state.isAutoFocus) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )

        // Full-screen result overlay shown after capture
        if (state.capturedBitmap != null) {
            CaptureResultOverlay(
                state = state,
                onToggleProcessed = { viewModel.toggleShowProcessed() },
                onDismiss = { viewModel.dismissCapture() },
            )
        }
    }
}

// ── Top controls ──────────────────────────────────────────────────────────────

@Composable
private fun TopControls(
    state: CameraState,
    onLongExposureToggle: (Boolean) -> Unit,
    onExposureChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Long-exposure toggle row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Exposure,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "Long Exposure", color = Color.White, fontSize = 14.sp)
            }
            Switch(
                checked = state.isLongExposure,
                onCheckedChange = onLongExposureToggle,
            )
        }

        // Exposure-time slider – visible only when long exposure is enabled
        if (state.isLongExposure) {
            Spacer(Modifier.height(4.dp))
            var sliderValue by remember(state.exposureTimeMs) {
                mutableFloatStateOf(state.exposureTimeMs.toFloat())
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Exposure: ${state.exposureTimeMs} ms",
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onExposureChange(sliderValue.toLong()) },
                    valueRange = 100f..4000f,
                    steps = 38,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}

// ── Bottom controls ───────────────────────────────────────────────────────────

@Composable
private fun BottomControls(
    state: CameraState,
    onCapture: () -> Unit,
    onSwitchLens: () -> Unit,
    onToggleFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Focus-mode toggle
        ControlButton(
            onClick = onToggleFocus,
            contentDescription = if (state.isAutoFocus) "Switch to manual focus"
                                 else "Switch to auto focus",
        ) {
            Icon(
                imageVector = if (state.isAutoFocus) Icons.Filled.CenterFocusStrong
                              else Icons.Filled.CenterFocusWeak,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }

        // Shutter button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(enabled = !state.isCapturing, onClick = onCapture),
        ) {
            if (state.isCapturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = Color.Black,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Camera,
                    contentDescription = "Capture",
                    tint = Color.Black,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // Lens-switch button
        ControlButton(
            onClick = onSwitchLens,
            contentDescription = if (state.isBackCamera) "Switch to front camera"
                                 else "Switch to back camera",
        ) {
            Icon(
                imageVector = if (state.isBackCamera) Icons.Filled.CameraFront
                              else Icons.Filled.CameraRear,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun ControlButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
    ) {
        content()
    }
}

// ── Capture result overlay ────────────────────────────────────────────────────

@Composable
private fun CaptureResultOverlay(
    state: CameraState,
    onToggleProcessed: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val displayBitmap: Bitmap? = if (state.showProcessed) {
            state.processedBitmap ?: state.capturedBitmap
        } else {
            state.capturedBitmap
        }

        displayBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Captured photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }

        // Label showing which version is displayed
        val label = when {
            state.showProcessed && state.processedBitmap != null -> "WebGPU Inverted"
            state.showProcessed -> "Processing…"
            else -> "Original"
        }
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )

        // Bottom action buttons
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            FilledTonalButton(
                onClick = onToggleProcessed,
                enabled = state.processedBitmap != null || !state.showProcessed,
            ) {
                Icon(
                    imageVector = Icons.Filled.FilterNone,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (state.showProcessed) "Show Original" else "WebGPU Invert")
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Close", color = Color.Black)
            }
        }
    }
}
