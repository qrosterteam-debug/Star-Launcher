/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.utils.device

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import com.movtery.zalithlauncher.utils.logging.Logger

private const val TAG = "GpuUtils"

/** GPU vendor classification used to recommend renderers and drivers. */
enum class GpuVendor {
    /** Qualcomm Adreno — Turnip/Zink capable on 6xx and newer */
    ADRENO,
    /** ARM Mali — best with the system GLES driver (GL4ES wrapper) */
    MALI,
    /** Imagination PowerVR */
    POWERVR,
    /** Samsung Xclipse (RDNA based) */
    XCLIPSE,
    /** Anything else or unknown */
    GENERIC
}

/** Result of a GPU probe. */
data class GpuInfo(
    val vendor: String?,
    val renderer: String?,
    val classified: GpuVendor
) {
    /** Adreno generation number (500, 600, 700, 800...) or null if not Adreno / unknown. */
    val adrenoGeneration: Int?
        get() {
            if (classified != GpuVendor.ADRENO) return null
            val match = Regex("(\\d{3})").find(renderer ?: return null) ?: return null
            return match.groupValues[1].toIntOrNull()
        }

    /** Human readable GPU name, falls back to the vendor string. */
    val displayName: String
        get() = renderer?.takeIf { it.isNotBlank() } ?: vendor ?: "Unknown"
}

/**
 * Detects the device GPU by creating a temporary EGL context and reading
 * GL_VENDOR / GL_RENDERER. Mirrors the approach used by isAdrenoGPU(),
 * but returns the full info instead of only an Adreno check.
 */
object GpuUtils {
    @Volatile
    private var cached: GpuInfo? = null

    /** Returns the (cached) GPU info. Thread-safe; performs the EGL probe at most once. */
    fun detect(): GpuInfo {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val info = probe()
            cached = info
            return info
        }
    }

    private fun probe(): GpuInfo {
        try {
            val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Logger.error(TAG, "Failed to get EGL display")
                return GpuInfo(null, null, GpuVendor.GENERIC)
            }
            if (!EGL14.eglInitialize(eglDisplay, null, 0, null, 0)) {
                Logger.error(TAG, "Failed to initialize EGL")
                return GpuInfo(null, null, GpuVendor.GENERIC)
            }

            val eglAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, eglAttributes, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                EGL14.eglTerminate(eglDisplay)
                Logger.error(TAG, "Failed to choose an EGL config")
                return GpuInfo(null, null, GpuVendor.GENERIC)
            }

            val contextAttributes = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            val context = EGL14.eglCreateContext(eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                EGL14.eglTerminate(eglDisplay)
                Logger.error(TAG, "Failed to create EGL context")
                return GpuInfo(null, null, GpuVendor.GENERIC)
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)) {
                EGL14.eglDestroyContext(eglDisplay, context)
                EGL14.eglTerminate(eglDisplay)
                Logger.error(TAG, "Failed to make EGL context current")
                return GpuInfo(null, null, GpuVendor.GENERIC)
            }

            val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)

            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, context)
            EGL14.eglTerminate(eglDisplay)

            val info = GpuInfo(vendor, renderer, classify(vendor, renderer))
            Logger.info(TAG, "GPU detected: vendor=$vendor renderer=$renderer class=${info.classified}")
            return info
        } catch (t: Throwable) {
            Logger.error(TAG, "GPU probe failed", t)
            return GpuInfo(null, null, GpuVendor.GENERIC)
        }
    }

    /** Classifies a GPU from the GL vendor/renderer strings. */
    fun classify(vendor: String?, renderer: String?): GpuVendor {
        val r = (renderer ?: "").lowercase()
        val v = (vendor ?: "").lowercase()
        return when {
            r.contains("adreno") || v.contains("qualcomm") -> GpuVendor.ADRENO
            r.contains("mali") || v.contains("arm") -> GpuVendor.MALI
            r.contains("powervr") || v.contains("imagination") -> GpuVendor.POWERVR
            r.contains("xclipse") -> GpuVendor.XCLIPSE
            else -> GpuVendor.GENERIC
        }
    }
}
