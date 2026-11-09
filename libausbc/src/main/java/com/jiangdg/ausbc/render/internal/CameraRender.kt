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
package com.jiangdg.ausbc.render.internal

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import com.jiangdg.ausbc.R
import com.jiangdg.ausbc.render.env.RotateType
import kotlin.math.cos
import kotlin.math.sin

/** Inherit from AbstractFboRender
 *      render camera data with camera_vertex.glsl and camera_fragment.glsl
 *
 * @author Created by jiangdg on 2021/12/27
 */
class CameraRender(context: Context) : AbstractFboRender(context) {
    private var mStMatrixHandle: Int = -1
    private var mMVPMatrixHandle: Int = -1
    private var mStMatrix = FloatArray(16)
    private var mMVPMatrix = FloatArray(16)
    private var mOESTextureId: Int = -1

    // Digital zoom: [scale, translateX, translateY] in normalized device coords.
    // Written from the UI thread, read on the GL thread -> one volatile array.
    @Volatile
    private var mZoom = floatArrayOf(1f, 0f, 0f)
    private val mZoomMatrix = FloatArray(16)
    private val mFinalMVPMatrix = FloatArray(16)

    override fun init() {
        mOESTextureId = createOESTexture()
        setMVPMatrix(0)
        Matrix.setIdentityM(mStMatrix, 0)
        mStMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uStMatrix")
        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
    }

    override fun beforeDraw() {
        val zoom = mZoom
        Matrix.setIdentityM(mZoomMatrix, 0)
        Matrix.translateM(mZoomMatrix, 0, zoom[1], zoom[2], 0f)
        Matrix.scaleM(mZoomMatrix, 0, zoom[0], zoom[0], 1f)
        Matrix.multiplyMM(mFinalMVPMatrix, 0, mZoomMatrix, 0, mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mFinalMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(mStMatrixHandle, 1, false, mStMatrix, 0)
    }

    override fun getBindTextureType(): Int {
        return GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    }

    override fun getVertexSourceId(): Int = R.raw.camera_vertex

    override fun getFragmentSourceId(): Int = R.raw.camera_fragment

    fun setRotateAngle(type: RotateType) {
        val angle = when (type) {
            RotateType.ANGLE_90 -> 90
            RotateType.ANGLE_180 -> 180
            RotateType.ANGLE_270 -> 270
            RotateType.FLIP_UP_DOWN -> -90
            RotateType.FLIP_LEFT_RIGHT -> -180
            else -> 0
        }
        setMVPMatrix(angle)
    }

    fun setTransformMatrix(matrix: FloatArray) {
        this.mStMatrix = matrix
    }

    private fun setMVPMatrix(angle: Int): FloatArray {
        Matrix.setIdentityM(mMVPMatrix, 0)
        when (angle) {
            -90 -> {
                // 上下翻转 (绕x轴180度)
                val radius = (180 * Math.PI / 180.0).toFloat()
                mMVPMatrix[5] *= cos(radius.toDouble()).toFloat()
                mMVPMatrix[6] += (-sin(radius.toDouble())).toFloat()
                mMVPMatrix[9] += sin(radius.toDouble()).toFloat()
                mMVPMatrix[10] *= cos(radius.toDouble()).toFloat()
            }
            -180 -> {
                // 左右翻转 (绕y轴180度)
                val radius = (180 * Math.PI / 180.0).toFloat()
                mMVPMatrix[0] *= cos(radius.toDouble()).toFloat()
                mMVPMatrix[2] += sin(radius.toDouble()).toFloat()
                mMVPMatrix[8] += (-sin(radius.toDouble())).toFloat()
                mMVPMatrix[10] *= cos(radius.toDouble()).toFloat()
            }
            else -> {
                // 旋转画面（绕z轴）
                val radius = (angle * Math.PI / 180.0).toFloat()
                mMVPMatrix[0] *= cos(radius.toDouble()).toFloat()
                mMVPMatrix[1] += (-sin(radius.toDouble())).toFloat()
                mMVPMatrix[4] += sin(radius.toDouble()).toFloat()
                mMVPMatrix[5] *= cos(radius.toDouble()).toFloat()
            }
        }
        return mMVPMatrix
    }

    /**
     * Digital zoom (crop + enlarge) applied to preview, photos and videos.
     *
     * @param scale >= 1, 1 = no zoom
     * @param tx horizontal offset in NDC (-(scale-1)..(scale-1))
     * @param ty vertical offset in NDC (-(scale-1)..(scale-1))
     */
    fun setZoom(scale: Float, tx: Float, ty: Float) {
        mZoom = floatArrayOf(scale, tx, ty)
    }

    fun getCameraTextureId() = mOESTextureId

    companion object {
        private const val TAG = "CameraRender"
    }
}