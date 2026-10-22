package com.novastudio24.spectratone.engine

import android.graphics.Bitmap

object NativeEngine {
    init {
        System.loadLibrary("spectratone")
    }

    external fun getEngineStatus(): String

    external fun applyAdjustments(
        srcBitmap: Bitmap,
        dstBitmap: Bitmap,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        warmth: Float,
        tint: Float,
        vignette: Float,
        presetId: Int,
        hslArray: FloatArray
    ): Boolean
}
