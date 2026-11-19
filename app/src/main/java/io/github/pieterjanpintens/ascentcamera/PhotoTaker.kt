package io.github.pieterjanpintens.ascentcamera

import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One JPEG from the back camera. `rotation` is how far (degrees, clockwise) the picture must be turned to stand upright for a phone held in portrait. */
class Photo(val jpeg: ByteArray, val rotation: Int)

/**
 * Takes single photos for the service. The camera is only switched on for the photo: it is bound to the service's lifecycle,
 * one picture is taken and it is unbound (closed) again, so between photos the camera draws no power.
 */
class PhotoTaker(private val service: LifecycleService) {
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    /** Blocks the calling (network) thread until the photo is there; throws when it fails or takes longer than `timeoutSeconds`. */
    fun take(timeoutSeconds: Long = 20): Photo {
        val result = CompletableFuture<Photo>()
        val main = ContextCompat.getMainExecutor(service)
        val providerFuture = ProcessCameraProvider.getInstance(service)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(service, CameraSelector.DEFAULT_BACK_CAMERA, capture)
                capture.takePicture(callbackExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            result.complete(Photo(bytes, image.imageInfo.rotationDegrees))
                        } catch (e: Exception) {
                            result.completeExceptionally(e)
                        } finally {
                            image.close()
                            main.execute { provider.unbindAll() }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        main.execute { provider.unbindAll() }
                        result.completeExceptionally(exception)
                    }
                })
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }, main)
        try {
            return result.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            main.execute { runCatching { ProcessCameraProvider.getInstance(service).get().unbindAll() } }
            throw e
        }
    }
}
