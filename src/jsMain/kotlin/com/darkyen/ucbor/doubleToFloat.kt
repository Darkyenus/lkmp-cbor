package com.darkyen.ucbor

import kotlinx.browser.window

internal actual fun doubleToFloat(v: Double): Float {
    // https://blog.mozilla.org/javascript/2013/11/07/efficient-float32-arithmetic-in-javascript/
    val fround = window.asDynamic().Math.fround
    if (fround !== undefined) {
        return fround(v).unsafeCast<Float>()
    }
    return v.toFloat()// Does nothing, but it is an acceptable fallback
}