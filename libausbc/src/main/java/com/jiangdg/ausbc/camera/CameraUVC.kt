/*
 * Copyright 2017-2023 Jiangdg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jiangdg.ausbc.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.MultiCameraClient.Companion.CAPTURE_TIMES_OUT_SEC
import com.jiangdg.ausbc.MultiCameraClient.Companion.MAX_NV21_DATA
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.utils.CameraUtils
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.MediaStoreUtils
import com.jiangdg.ausbc.utils.MediaUtils
import com.jiangdg.ausbc.utils.Utils
import com.jiangdg.uvc.IFrameCallback
import com.jiangdg.uvc.UVCCamera
import java.io.File
import java.util.concurrent.TimeUnit

/** UVC Camera
 *
 * @author Created by jiangdg on 2023/1/15
 */
class CameraUVC(ctx: Context, device: UsbDevice) : MultiCameraClient.ICamera(ctx, device) {
    private var mUvcCamera: UVCCamera? = null

    private val frameCallBack = IFrameCallback { frame ->
        frame?.apply {
            frame.position(0)
            val data = ByteArray(capacity())
            get(data)
            mCameraRequest?.apply {
                if (data.size != previewWidth * previewHeight * 3 / 2) {
                    return@IFrameCallback
                }
                // for preview callback
                mPreviewDataCbList.forEach { cb ->
                    cb?.onPreviewData(data, previewWidth, previewHeight, IPreviewDataCallBack.DataFormat.NV21)
                }
                // for image
                if (mNV21DataQueue.size >= MAX_NV21_DATA) {
                    mNV21DataQueue.removeLast()
                }
                mNV21DataQueue.offerFirst(data)
                // for video
                // avoid preview size changed
                putVideoData(data)
            }
        }
    }

    override fun getAllPreviewSizes(aspectRatio: Double?): MutableList<PreviewSize> {
        val previewSizeList = arrayListOf<PreviewSize>()
        val uvcCamera = mUvcCamera
        if (uvcCamera == null) {
            Logger.w(TAG, "getAllPreviewSizes: camera not opened, device=${device.deviceName}")
            return previewSizeList
        }

        val rawSupportedSize = uvcCamera.supportedSize
        Logger.i(TAG, "getAllPreviewSizes raw=$rawSupportedSize, device=${device.deviceName}")

        val uniqueSizes = linkedSetOf<Pair<Int, Int>>()
        uvcCamera.allSupportedSizeList?.forEach { size ->
            uniqueSizes.add(size.width to size.height)
        }
        uvcCamera.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG)?.forEach { size ->
            uniqueSizes.add(size.width to size.height)
        }
        uvcCamera.getSupportedSizeList(UVCCamera.FRAME_FORMAT_YUYV)?.forEach { size ->
            uniqueSizes.add(size.width to size.height)
        }

        uniqueSizes.forEach { (width, height) ->
            val ratio = width.toDouble() / height
            if (aspectRatio == null || aspectRatio == ratio) {
                previewSizeList.add(PreviewSize(width, height))
            }
        }
        previewSizeList.sortByDescending { it.width * it.height }
        Logger.i(TAG, "getAllPreviewSizes result=$previewSizeList")
        return previewSizeList
    }

    private fun logDeviceInfo() {
        Logger.i(
            TAG,
            "USB device: name=${device.deviceName}, vid=0x${device.vendorId.toString(16)}, " +
                "pid=0x${device.productId.toString(16)}, class=${device.deviceClass}, " +
                "subclass=${device.deviceSubclass}, protocol=${device.deviceProtocol}"
        )
    }

    private fun trySetPreviewSize(width: Int, height: Int, frameFormat: Int): Boolean {
        val bandwidths = floatArrayOf(1.0f, 0.8f, 0.6f, 0.5f, 0.3f)
        val maxFpsOptions = intArrayOf(
            UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
            25,
            20,
            15,
            10,
            5
        )
        for (bandwidth in bandwidths) {
            for (maxFps in maxFpsOptions) {
                try {
                    mUvcCamera?.setPreviewSize(
                        width,
                        height,
                        MIN_FS,
                        maxFps,
                        frameFormat,
                        bandwidth
                    )
                    Logger.i(
                        TAG,
                        "setPreviewSize OK: ${width}x${height}, format=$frameFormat, " +
                            "fps=$MIN_FS-$maxFps, bandwidth=$bandwidth"
                    )
                    return true
                } catch (e: Exception) {
                    Logger.w(
                        TAG,
                        "setPreviewSize failed: ${width}x${height}, format=$frameFormat, " +
                            "fps=$MIN_FS-$maxFps, bandwidth=$bandwidth, err=${e.message}"
                    )
                }
            }
        }
        return false
    }

    private fun resolvePreviewSize(request: CameraRequest): PreviewSize {
        val allSizes = getAllPreviewSizes()
        if (allSizes.isEmpty()) {
            Logger.w(TAG, "resolvePreviewSize: no supported sizes, fallback to request size")
            return PreviewSize(request.previewWidth, request.previewHeight)
        }
        return getSuitableSize(request.previewWidth, request.previewHeight)
    }

    override fun <T> openCameraInternal(cameraView: T) {
        if (Utils.isTargetSdkOverP(ctx) && !CameraUtils.hasCameraPermission(ctx)) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "Has no CAMERA permission.")
            Logger.e(TAG,"open camera failed, need Manifest.permission.CAMERA permission when targetSdk>=28")
            return
        }
        if (mCtrlBlock == null) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "Usb control block can not be null ")
            return
        }
        // 1. create a UVCCamera
        val request = mCameraRequest!!
        logDeviceInfo()
        try {
            mUvcCamera = UVCCamera().apply {
                open(mCtrlBlock)
            }
            Logger.i(TAG, "UVCCamera opened, supportedSize=${mUvcCamera?.supportedSize}")
            Logger.i(TAG, "allSupportedSizeList=${mUvcCamera?.allSupportedSizeList}")
        } catch (e: Exception) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "open camera failed ${e.localizedMessage}")
            Logger.e(TAG, "open camera failed.", e)
            return
        }

        // 2. set preview size and register preview callback
        var previewSize = resolvePreviewSize(request).apply {
            mCameraRequest!!.previewWidth = width
            mCameraRequest!!.previewHeight = height
        }
        val preferMjpeg = mCameraRequest?.previewFormat != CameraRequest.PreviewFormat.FORMAT_YUYV
        val primaryFormat = if (preferMjpeg) {
            UVCCamera.FRAME_FORMAT_MJPEG
        } else {
            UVCCamera.FRAME_FORMAT_YUYV
        }
        val fallbackFormat = if (primaryFormat == UVCCamera.FRAME_FORMAT_MJPEG) {
            UVCCamera.FRAME_FORMAT_YUYV
        } else {
            UVCCamera.FRAME_FORMAT_MJPEG
        }

        Logger.i(TAG, "getSuitableSize: $previewSize, supported=${getAllPreviewSizes()}")
        if (!isPreviewSizeSupported(previewSize)) {
            val firstSupported = getAllPreviewSizes().firstOrNull()
            if (firstSupported != null) {
                Logger.w(TAG, "preview size($previewSize) unsupported, fallback to $firstSupported")
                previewSize = firstSupported.apply {
                    mCameraRequest!!.previewWidth = width
                    mCameraRequest!!.previewHeight = height
                }
            } else {
                closeCamera()
                postStateEvent(ICameraStateCallBack.State.ERROR, "unsupported preview size")
                Logger.e(
                    TAG,
                    "open camera failed, no supported preview size. raw=${mUvcCamera?.supportedSize}"
                )
                return
            }
        }

        initEncodeProcessor(previewSize.width, previewSize.height)
        var previewConfigured = trySetPreviewSize(previewSize.width, previewSize.height, primaryFormat)
        if (!previewConfigured) {
            Logger.w(TAG, "primary format($primaryFormat) failed, try fallback format($fallbackFormat)")
            previewConfigured = trySetPreviewSize(previewSize.width, previewSize.height, fallbackFormat)
        }
        if (!previewConfigured) {
            val fallbackSize = getAllPreviewSizes()
                .sortedBy { it.width * it.height }
                .firstOrNull { size ->
                    size.width != previewSize.width || size.height != previewSize.height
                }
            if (fallbackSize != null) {
                Logger.w(TAG, "retry with smaller preview size: $fallbackSize")
                previewSize = fallbackSize.apply {
                    mCameraRequest!!.previewWidth = width
                    mCameraRequest!!.previewHeight = height
                }
                initEncodeProcessor(previewSize.width, previewSize.height)
                previewConfigured = trySetPreviewSize(previewSize.width, previewSize.height, primaryFormat)
                    || trySetPreviewSize(previewSize.width, previewSize.height, fallbackFormat)
            }
        }
        if (!previewConfigured) {
            closeCamera()
            postStateEvent(
                ICameraStateCallBack.State.ERROR,
                "setPreviewSize failed for all format/bandwidth combinations"
            )
            Logger.e(
                TAG,
                "setPreviewSize failed, supported=${getAllPreviewSizes()}, raw=${mUvcCamera?.supportedSize}"
            )
            return
        }
        // if not opengl render or opengl render with preview callback
        // there should opened
        if (! isNeedGLESRender || mCameraRequest!!.isRawPreviewData || mCameraRequest!!.isCaptureRawImage) {
            mUvcCamera?.setFrameCallback(frameCallBack, UVCCamera.PIXEL_FORMAT_YUV420SP)
        }
        // 3. start preview
        when(cameraView) {
            is Surface -> {
                mUvcCamera?.setPreviewDisplay(cameraView)
            }
            is SurfaceTexture -> {
                mUvcCamera?.setPreviewTexture(cameraView)
            }
            is SurfaceView -> {
                mUvcCamera?.setPreviewDisplay(cameraView.holder)
            }
            is TextureView -> {
                mUvcCamera?.setPreviewTexture(cameraView.surfaceTexture)
            }
            else -> {
                throw IllegalStateException("Only support Surface or SurfaceTexture or SurfaceView or TextureView or GLSurfaceView--$cameraView")
            }
        }
        mUvcCamera?.autoFocus = true
        mUvcCamera?.autoWhiteBlance = true
        mUvcCamera?.startPreview()
        mUvcCamera?.updateCameraParams()
        isPreviewed = true
        postStateEvent(ICameraStateCallBack.State.OPENED)
        if (Utils.debugCamera) {
            Logger.i(TAG, " start preview, name = ${device.deviceName}, preview=$previewSize")
        }
    }

    override fun closeCameraInternal() {
        postStateEvent(ICameraStateCallBack.State.CLOSED)
        isPreviewed = false
        releaseEncodeProcessor()
        mUvcCamera?.destroy()
        mUvcCamera = null
        if (Utils.debugCamera) {
            Logger.i(TAG, " stop preview, name = ${device.deviceName}")
        }
    }

    override fun captureImageInternal(savePath: String?, callback: ICaptureCallBack) {
        mSaveImageExecutor.submit {
            if (savePath.isNullOrEmpty()) {
                File(mCameraDir).apply { if(!exists()) mkdirs() }
            }
            if (! isPreviewed) {
                mMainHandler.post {
                    callback.onError("camera not previewing")
                }
                Logger.i(TAG, "captureImageInternal failed, camera not previewing")
                return@submit
            }
            val data = mNV21DataQueue.pollFirst(CAPTURE_TIMES_OUT_SEC, TimeUnit.SECONDS)
            if (data == null) {
                mMainHandler.post {
                    callback.onError("Times out")
                }
                Logger.i(TAG, "captureImageInternal failed, times out.")
                return@submit
            }
            mMainHandler.post {
                callback.onBegin()
            }
            val date = mDateFormat.format(System.currentTimeMillis())
            val width = mCameraRequest!!.previewWidth
            val height = mCameraRequest!!.previewHeight
            val path = savePath ?: "$mCameraDir/IMG_AUSBC_${date}_w${width}_h${height}.jpg"
            val ret = MediaUtils.saveYuv2Jpeg(path, data, width, height)
            if (! ret) {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
                mMainHandler.post {
                    callback.onError("save yuv to jpeg failed.")
                }
                Logger.w(TAG, "save yuv to jpeg failed.")
                return@submit
            }
            mMainHandler.post {
                callback.onComplete(path)
            }
            if (Utils.debugCamera) { Logger.i(TAG, "captureImageInternal save path = $path") }
        }
    }

    /**
     * Is mic supported
     *
     * @return true camera support mic
     */
    fun isMicSupported() = CameraUtils.isCameraContainsMic(this.device)

    /**
     * Send camera command
     *
     * This method cannot be verified, please use it with caution
     */
    fun sendCameraCommand(command: Int) {
        mCameraHandler?.post {
            mUvcCamera?.sendCommand(command)
        }
    }

    /**
     * Set auto focus
     *
     * @param enable true enable auto focus
     */
    fun setAutoFocus(enable: Boolean) {
        mUvcCamera?.autoFocus = enable
    }

    /**
     * Get auto focus
     *
     * @return true enable auto focus
     */
    fun getAutoFocus() = mUvcCamera?.autoFocus

    /**
     * Reset auto focus
     */
    fun resetAutoFocus() {
        mUvcCamera?.resetFocus()
    }

    /**
     * Set auto white balance
     *
     * @param autoWhiteBalance true enable auto white balance
     */
    fun setAutoWhiteBalance(autoWhiteBalance: Boolean) {
        mUvcCamera?.autoWhiteBlance = autoWhiteBalance
    }

    /**
     * Get auto white balance
     *
     * @return true enable auto white balance
     */
    fun getAutoWhiteBalance() = mUvcCamera?.autoWhiteBlance

    /**
     * Set zoom
     *
     * @param zoom zoom value, 0 means reset
     */
    fun setZoom(zoom: Int) {
        mUvcCamera?.zoom = zoom
    }

    /**
     * Get zoom
     */
    fun getZoom() = mUvcCamera?.zoom

    /**
     * Reset zoom
     */
    fun resetZoom() {
        mUvcCamera?.resetZoom()
    }

    /**
     * Set gain
     *
     * @param gain gain value, 0 means reset
     */
    fun setGain(gain: Int) {
        mUvcCamera?.gain = gain
    }

    /**
     * Get gain
     */
    fun getGain() = mUvcCamera?.gain

    /**
     * Reset gain
     */
    fun resetGain() {
        mUvcCamera?.resetGain()
    }

    /**
     * Set gamma
     *
     * @param gamma gamma value, 0 means reset
     */
    fun setGamma(gamma: Int) {
        mUvcCamera?.gamma = gamma
    }

    /**
     * Get gamma
     */
    fun getGamma() = mUvcCamera?.gamma

    /**
     * Reset gamma
     */
    fun resetGamma() {
        mUvcCamera?.resetGamma()
    }

    /**
     * Set brightness
     *
     * @param brightness brightness value, 0 means reset
     */
    fun setBrightness(brightness: Int) {
        mUvcCamera?.brightness = brightness
    }

    /**
     * Get brightness
     */
    fun getBrightness() = mUvcCamera?.brightness
    
    fun getBrightnessMax() = mUvcCamera?.brightnessMax

    fun getBrightnessMin() = mUvcCamera?.brightnessMin
    
    /**
     * Reset brightnes
     */
    fun resetBrightness() {
        mUvcCamera?.resetBrightness()
    }

    /**
     * Set contrast
     *
     * @param contrast contrast value, 0 means reset
     */
    fun setContrast(contrast: Int) {
        mUvcCamera?.contrast = contrast
    }

    /**
     * Get contrast
     */
    fun getContrast() = mUvcCamera?.contrast

    /**
     * Reset contrast
     */
    fun resetContrast() {
        mUvcCamera?.resetContrast()
    }

    /**
     * Set sharpness
     *
     * @param sharpness sharpness value, 0 means reset
     */
    fun setSharpness(sharpness: Int) {
        mUvcCamera?.sharpness = sharpness
    }

    /**
     * Get sharpness
     */
    fun getSharpness() = mUvcCamera?.sharpness

    /**
     * Reset sharpness
     */
    fun resetSharpness() {
        mUvcCamera?.resetSharpness()
    }

    /**
     * Set saturation
     *
     * @param saturation saturation value, 0 means reset
     */
    fun setSaturation(saturation: Int) {
        mUvcCamera?.saturation = saturation
    }

    /**
     * Get saturation
     */
    fun getSaturation() = mUvcCamera?.saturation

    /**
     * Reset saturation
     */
    fun resetSaturation() {
        mUvcCamera?.resetSaturation()
    }

    /**
     * Set hue
     *
     * @param hue hue value, 0 means reset
     */
    fun setHue(hue: Int) {
        mUvcCamera?.hue = hue
    }

    /**
     * Get hue
     */
    fun getHue() = mUvcCamera?.hue

    /**
     * Reset saturation
     */
    fun resetHue() {
        mUvcCamera?.resetHue()
    }

    companion object {
        private const val TAG = "CameraUVC"
        private const val MIN_FS = 1
    }
}