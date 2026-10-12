package io.islandbitcoin.foxy.scanner

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import io.islandbitcoin.foxy.debug.Log

/**
 * How both scanners use the camera (QRScannerViewController.swift,
 * LiveScanner.swift): as many pixels as the phone will give for the decoder,
 * 4K and then 1080p, because a dense code is the hard case (a Cashu request is
 * 61 modules across, and under about three pixels a module the decoder never
 * locks on); and close focus, zoomed until a code of a realistic size fills
 * enough of the frame from the nearest the lens focuses ([closeFocus]).
 */
object ScanCamera {
    /** The decoder's frames: 4K, else the closest below it, the latest frame only. */
    fun analysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(3840, 2160), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    /** Zooms for close focus where the main lens cannot focus a hand's width away; leaves a lens that can alone. */
    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalCamera2Interop::class)
    fun focusClose(camera: Camera) {
        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val diopters = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: return
            val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return
            // a fixed-focus lens says 0 diopters: nothing to do
            if (diopters <= 0f || focal <= 0f) return
            val closestMm = 1000.0 / diopters
            val halfAngle = Math.atan(sensor.width / (2.0 * focal))
            val most = camera.cameraInfo.zoomState.value?.maxZoomRatio?.toDouble() ?: 1.0
            val zoom = closeFocus(closestMm, halfAngle, most)
            Log.line("[scan] closest focus ${closestMm.toInt()}mm, zoom ${"%.2f".format(zoom)}")
            if (zoom > 1.0) camera.cameraControl.setZoomRatio(zoom.toFloat())
        } catch (e: RuntimeException) {
            Log.line("[scan] the camera would not say how close it focuses (${e.javaClass.simpleName})")
        }
    }
}
