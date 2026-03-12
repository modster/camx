package com.modster.camx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.hardware.camera2.CaptureRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Represents the current UI state of the camera screen.
 */
data class CameraState(
    /** Whether long-exposure mode is active. */
    val isLongExposure: Boolean = false,
    /** Long-exposure time in milliseconds (100–4000). */
    val exposureTimeMs: Long = 500L,
    /** True = continuous auto-focus; false = manual tap-to-focus. */
    val isAutoFocus: Boolean = true,
    /** True = back camera selected. */
    val isBackCamera: Boolean = true,
    /** True while a capture is in progress. */
    val isCapturing: Boolean = false,
    /** Bitmap captured from the camera (null until a photo is taken). */
    val capturedBitmap: Bitmap? = null,
    /** Bitmap after WebGPU color-inversion processing. */
    val processedBitmap: Bitmap? = null,
    /** When true, display the processed (inverted) image instead of the original. */
    val showProcessed: Boolean = false,
)

/**
 * ViewModel that owns all camera state and orchestrates CameraX use-cases.
 */
class CameraViewModel : ViewModel() {

    companion object {
        private const val TAG = "CameraViewModel"
    }

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** CameraX ImageCapture use-case, re-created when settings change. */
    private var imageCapture: ImageCapture? = null

    /** Active Camera handle used for focus/metering. */
    private var camera: Camera? = null

    /** CameraProvider reference so we can unbind/rebind when settings change. */
    private var cameraProvider: ProcessCameraProvider? = null

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── State mutators ────────────────────────────────────────────────────────

    fun setLongExposure(enabled: Boolean) {
        _state.value = _state.value.copy(isLongExposure = enabled)
    }

    fun setExposureTimeMs(ms: Long) {
        _state.value = _state.value.copy(exposureTimeMs = ms)
    }

    fun setAutoFocus(auto: Boolean) {
        _state.value = _state.value.copy(isAutoFocus = auto)
        if (auto) {
            camera?.cameraControl?.cancelFocusAndMetering()
        }
    }

    // ── Camera binding ────────────────────────────────────────────────────────

    /**
     * Binds (or re-binds) the camera to [lifecycleOwner].
     * Must be called on the main thread.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(
        context: Context,
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val cameraSelector = if (_state.value.isBackCamera) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            // Preview use-case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // ImageCapture use-case – optionally configured for long exposure
            val imageCaptureBuilder = ImageCapture.Builder()

            if (_state.value.isLongExposure) {
                Camera2Interop.Extender(imageCaptureBuilder).apply {
                    // Disable auto-exposure so we can set manual shutter speed
                    setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF,
                    )
                    // Convert milliseconds → nanoseconds for the sensor
                    setCaptureRequestOption(
                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                        _state.value.exposureTimeMs * 1_000_000L,
                    )
                    // Use low ISO to reduce noise during long exposures
                    setCaptureRequestOption(
                        CaptureRequest.SENSOR_SENSITIVITY,
                        100,
                    )
                }
            }

            imageCapture = imageCaptureBuilder.build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Switches between back and front cameras and re-binds camera use-cases.
     */
    fun switchCamera(
        context: Context,
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    ) {
        _state.value = _state.value.copy(isBackCamera = !_state.value.isBackCamera)
        bindCamera(context, previewView, lifecycleOwner)
    }

    // ── Focus ─────────────────────────────────────────────────────────────────

    /**
     * Triggers a manual tap-to-focus at the given [previewView] coordinates.
     * Only active when [CameraState.isAutoFocus] is false.
     */
    fun tapToFocus(x: Float, y: Float, previewView: PreviewView) {
        if (_state.value.isAutoFocus) return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    /**
     * Takes a photo and delivers the decoded [Bitmap] via [onBitmapReady].
     */
    fun capturePhoto(onBitmapReady: (Bitmap) -> Unit) {
        val capture = imageCapture ?: return
        _state.value = _state.value.copy(isCapturing = true)

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    viewModelScope.launch {
                        _state.value = _state.value.copy(
                            isCapturing = false,
                            capturedBitmap = bitmap,
                        )
                        onBitmapReady(bitmap)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed: ${exception.message}", exception)
                    viewModelScope.launch {
                        _state.value = _state.value.copy(isCapturing = false)
                    }
                }
            },
        )
    }

    /** Stores the WebGPU-processed (color-inverted) bitmap and shows it. */
    fun setProcessedBitmap(bitmap: Bitmap) {
        _state.value = _state.value.copy(
            processedBitmap = bitmap,
            showProcessed = true,
        )
    }

    /** Toggles between showing the original and the processed image. */
    fun toggleShowProcessed() {
        _state.value = _state.value.copy(showProcessed = !_state.value.showProcessed)
    }

    /** Clears the captured / processed images and returns to the live preview. */
    fun dismissCapture() {
        _state.value = _state.value.copy(
            capturedBitmap = null,
            processedBitmap = null,
            showProcessed = false,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override fun onCleared() {
        super.onCleared()
        cameraExecutor.shutdown()
    }
}
