/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors.
 * Star Launcher setup wizard modifications.
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

package com.movtery.zalithlauncher.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.movtery.zalithlauncher.game.plugin.PluginLoader
import com.movtery.zalithlauncher.game.plugin.driver.DriverFeed
import com.movtery.zalithlauncher.game.plugin.driver.DriverFeedEntry
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.renderer.RendererInterface
import com.movtery.zalithlauncher.game.renderer.renderers.KopperZinkRenderer
import com.movtery.zalithlauncher.game.renderer.renderers.NGGL4ESRenderer
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.device.Architecture
import com.movtery.zalithlauncher.utils.device.GpuInfo
import com.movtery.zalithlauncher.utils.device.GpuUtils
import com.movtery.zalithlauncher.utils.device.GpuVendor
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.platform.getTotalMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SetupWizardViewModel"

/** Steps of the first-run setup wizard, in order. */
enum class SetupStep {
    WELCOME, ACCOUNT, PERMISSIONS, HARDWARE, DRIVER, FINISH;

    val index: Int get() = ordinal
}

/** State of the community driver feed in the driver step. */
sealed interface DriverFeedState {
    data object Idle : DriverFeedState
    data object Loading : DriverFeedState
    data class Ready(val entries: List<DriverFeedEntry>) : DriverFeedState
    data class Failed(val message: String) : DriverFeedState
}

/**
 * State holder for the first-run setup wizard.
 * Activity-scoped so progress survives navigating away (Microsoft login web
 * screen, leaving to the system package installer, ...).
 */
class SetupWizardViewModel : ViewModel() {

    var step by mutableStateOf(SetupStep.WELCOME)
        private set

    /** Local account username input. */
    var localUserName by mutableStateOf("")
    /** Set when the Microsoft login flow was kicked off; auto-advances on success. */
    var awaitingMicrosoftLogin by mutableStateOf(false)

    // ---- hardware scan
    /** True while the hardware scan runs (used for the scanning animation). */
    var scanning by mutableStateOf(false)
    var gpuInfo by mutableStateOf<GpuInfo?>(null)
    var socName by mutableStateOf("")
    var totalMemoryMB by mutableStateOf(0L)
    var cpuCores by mutableStateOf(0)
    var androidVersion by mutableStateOf("")
    var is64Bit by mutableStateOf(true)

    // ---- driver selection
    var driverFeedState by mutableStateOf<DriverFeedState>(DriverFeedState.Idle)
        private set
    /** Entry currently being downloaded (for row progress). */
    var downloadingEntry by mutableStateOf<DriverFeedEntry?>(null)
    /** Bytes downloaded / total for the active download. */
    var downloadProgress by mutableStateOf(0f)
    /** Set when the system installer was opened for an APK. */
    var installPromptShown by mutableStateOf(false)

    /** Selected renderer id (unique identifier), defaults to the recommendation. */
    var selectedRendererId by mutableStateOf<String?>(null)
    /** Selected driver id ("default turnip" or an installed plugin package name). */
    var selectedDriverId by mutableStateOf(ALL_DRIVERS)

    /** True when this instance already guided the user to the very end. */
    var completed by mutableStateOf(false)
        private set

    private var scanJob: kotlinx.coroutines.Job? = null
    private var feedJob: kotlinx.coroutines.Job? = null
    private var downloadJob: kotlinx.coroutines.Job? = null

    /** Resets everything (used when the wizard is re-run from settings). */
    fun reset() {
        step = SetupStep.WELCOME
        localUserName = ""
        awaitingMicrosoftLogin = false
        gpuInfo = null
        driverFeedState = DriverFeedState.Idle
        downloadingEntry = null
        downloadProgress = 0f
        installPromptShown = false
        selectedRendererId = null
        selectedDriverId = ALL_DRIVERS
    }

    fun goToStep(target: SetupStep) {
        step = target
    }

    fun nextStep() {
        val next = SetupStep.entries.getOrNull(step.ordinal + 1) ?: return
        // the driver step is only meaningful for Turnip-capable GPUs; skip it otherwise
        step = if (next == SetupStep.DRIVER && !showDriverSelection()) {
            SetupStep.FINISH
        } else {
            next
        }
    }

    fun previousStep() {
        val prev = SetupStep.entries.getOrNull(step.ordinal - 1) ?: return
        step = if (prev == SetupStep.DRIVER && !showDriverSelection()) {
            SetupStep.HARDWARE
        } else {
            prev
        }
    }

    // --------------------------------------------------------------- hardware

    /**
     * Runs the hardware scan. The EGL probe is executed off the main thread;
     * a small minimum duration keeps the animation pleasant.
     */
    fun startScan(scope: kotlinx.coroutines.CoroutineScope, context: Context) {
        if (scanning) return
        if (gpuInfo != null) return // already scanned
        scanJob?.cancel()
        scanJob = scope.launch {
            scanning = true
            try {
                val started = System.currentTimeMillis()
                val info = withContext(Dispatchers.Default) { GpuUtils.detect() }
                gpuInfo = info
                socName = withContext(Dispatchers.Default) {
                    runCatching { com.movtery.zalithlauncher.game.launch.getSocName() }.getOrDefault("")
                }
                totalMemoryMB = withContext(Dispatchers.Default) {
                    runCatching { getTotalMemory(context) }.getOrDefault(0L)
                }
                cpuCores = Runtime.getRuntime().availableProcessors()
                androidVersion = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
                is64Bit = Architecture.is64BitsDevice
                // keep the scan visible for at least ~1.2s
                val elapsed = System.currentTimeMillis() - started
                if (elapsed < 1200L) delay(1200L - elapsed)
                // lazily default the selections to the recommendation
                if (selectedRendererId == null) selectedRendererId = recommendedRendererId() ?: NGGL4ESRenderer.getUniqueIdentifier()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.error(TAG, "Hardware scan failed", t)
                gpuInfo = GpuInfo(null, null, GpuVendor.GENERIC)
            } finally {
                scanning = false
            }
        }
    }

    /**
     * The recommended renderer for the detected GPU, or null when the scan
     * has not finished yet.
     *
     * - Adreno 6xx and newer: Zink (Kopper) over the Turnip Vulkan driver —
     *   desktop-class GL 4.6 for modern Minecraft versions.
     * - Mali / PowerVR / Xclipse / anything else: Krypton Wrapper (GL4ES)
     *   on the system GLES driver — best compatibility, exactly what these
     *   GPUs are tuned for.
     */
    fun recommendedRendererId(): String? {
        val info = gpuInfo ?: return null
        return when (info.classified) {
            GpuVendor.ADRENO -> {
                val gen = info.adrenoGeneration
                if (gen == null || gen >= 600) KopperZinkRenderer.getUniqueIdentifier()
                else NGGL4ESRenderer.getUniqueIdentifier()
            }
            else -> NGGL4ESRenderer.getUniqueIdentifier()
        }
    }

    /** True when the driver step should offer the Turnip driver selection (Adreno 6xx+). */
    fun showDriverSelection(): Boolean {
        val info = gpuInfo ?: return false
        if (info.classified != GpuVendor.ADRENO) return false
        val generation = info.adrenoGeneration ?: return true // unknown generation: offer, bundled default stays safe
        return generation >= 600
    }

    // --------------------------------------------------------------- driver feed

    fun loadDriverFeed(scope: kotlinx.coroutines.CoroutineScope) {
        if (driverFeedState is DriverFeedState.Loading || driverFeedState is DriverFeedState.Ready) return
        feedJob?.cancel()
        feedJob = scope.launch {
            driverFeedState = DriverFeedState.Loading
            try {
                val entries = DriverFeed.fetch()
                driverFeedState = if (entries.isEmpty()) {
                    DriverFeedState.Failed("EMPTY")
                } else {
                    DriverFeedState.Ready(entries)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.error(TAG, "Driver feed failed", t)
                driverFeedState = DriverFeedState.Failed(t.message ?: t::class.java.simpleName ?: "error")
            }
        }
    }

    fun retryDriverFeed(scope: kotlinx.coroutines.CoroutineScope) {
        driverFeedState = DriverFeedState.Idle
        loadDriverFeed(scope)
    }

    /**
     * Downloads a driver APK into the app cache dir and hands it to the
     * system installer. The wizard stays on the driver step; when the user
     * returns, [rescanPlugins] will pick the newly installed plugin up.
     */
    fun downloadAndInstall(
        scope: kotlinx.coroutines.CoroutineScope,
        context: Context,
        entry: DriverFeedEntry,
        onReadyToInstall: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        if (downloadingEntry != null) return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            downloadingEntry = entry
            downloadProgress = 0f
            try {
                val target = File(context.cacheDir, "driver_plugin_${entry.name}")
                if (target.exists()) target.delete()
                withContext(Dispatchers.IO) {
                    com.movtery.zalithlauncher.utils.network.downloadFile(
                        url = entry.downloadUrl,
                        outputFile = target,
                        sizeCallback = { downloaded ->
                            if (entry.sizeBytes > 0) {
                                downloadProgress = (downloaded.toFloat() / entry.sizeBytes).coerceIn(0f, 1f)
                            }
                        }
                    )
                }
                downloadProgress = 1f
                withContext(Dispatchers.Main) {
                    onReadyToInstall(target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.error(TAG, "Driver download failed", t)
                withContext(Dispatchers.Main) {
                    onError(t.message ?: t::class.java.simpleName ?: "error")
                }
            } finally {
                downloadingEntry = null
            }
        }
    }

    /** Rescans installed plugins (drivers, renderers) after returning from the installer. */
    fun rescanPlugins(context: Context) {
        runCatching {
            PluginLoader.loadAllPlugins(context, true)
        }.onFailure {
            Logger.error(TAG, "Plugin rescan failed", it)
        }
    }

    // --------------------------------------------------------------- applying

    /**
     * Persists the wizard choices. Called on the finish step.
     */
    fun applySettings(context: Context) {
        val rendererId = selectedRendererId ?: recommendedRendererId() ?: NGGL4ESRenderer.getUniqueIdentifier()
        AllSettings.renderer.save(rendererId)
        runCatching {
            Renderers.setCurrentRenderer(rendererId)
        }.onFailure {
            Logger.error(TAG, "setCurrentRenderer failed", it)
        }

        val driverId = selectedDriverId
        AllSettings.vulkanDriver.save(driverId)

        AllSettings.setupCompleted.save(true)
        completed = true
        Logger.info(TAG, "Setup completed: renderer=$rendererId driver=$driverId")
    }

    /** Skips the wizard, applying safe defaults (Krypton renderer, bundled driver). */
    fun skipSetup(context: Context) {
        AllSettings.renderer.save(NGGL4ESRenderer.getUniqueIdentifier())
        runCatching { Renderers.setCurrentRenderer(NGGL4ESRenderer.getUniqueIdentifier()) }
        AllSettings.vulkanDriver.save(ALL_DRIVERS)
        AllSettings.setupCompleted.save(true)
        completed = true
    }

    fun rendererById(id: String?): RendererInterface? {
        if (id == null) return null
        return Renderers.getRenderers().find { it.getUniqueIdentifier() == id }
    }

    companion object {
        /** The bundled Turnip driver id shipped inside the launcher APK. */
        const val ALL_DRIVERS = "default turnip"
    }
}
