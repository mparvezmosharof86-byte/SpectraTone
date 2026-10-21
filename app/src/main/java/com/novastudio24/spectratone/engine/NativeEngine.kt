package com.novastudio24.spectratone.engine

object NativeEngine {
    init {
        System.loadLibrary("spectratone")
    }

    external fun getEngineStatus(): String
}
