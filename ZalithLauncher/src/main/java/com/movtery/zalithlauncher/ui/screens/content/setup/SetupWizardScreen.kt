/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors.
 * Star Launcher first-run setup wizard.
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

package com.movtery.zalithlauncher.ui.screens.content.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.getUUIDFromUserName
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.game.account.isMicrosoftLogging
import com.movtery.zalithlauncher.game.account.localLogin
import com.movtery.zalithlauncher.game.account.microsoftLogin
import com.movtery.zalithlauncher.game.plugin.driver.DriverFeed
import com.movtery.zalithlauncher.game.plugin.driver.DriverFeedEntry
import com.movtery.zalithlauncher.game.plugin.driver.DriverPluginManager
import com.movtery.zalithlauncher.game.renderer.renderers.KopperZinkRenderer
import com.movtery.zalithlauncher.game.renderer.renderers.NGGL4ESRenderer
import com.movtery.zalithlauncher.path.URL_GITHUB_DRIVER_PLUGINS
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.ui.components.OwnOutlinedTextField
import com.movtery.zalithlauncher.ui.components.RadioCard
import com.movtery.zalithlauncher.ui.components.ScalingActionButton
import com.movtery.zalithlauncher.ui.components.TitleAndSummary
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.content.navigateToWeb
import com.movtery.zalithlauncher.utils.checkStoragePermissions
import com.movtery.zalithlauncher.utils.hasStoragePermissions
import com.movtery.zalithlauncher.utils.device.GpuVendor
import com.movtery.zalithlauncher.utils.file.formatFileSize
import com.movtery.zalithlauncher.viewmodel.DriverFeedState
import com.movtery.zalithlauncher.viewmodel.ErrorViewModel
import com.movtery.zalithlauncher.viewmodel.EventViewModel
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel
import com.movtery.zalithlauncher.viewmodel.SetupStep
import com.movtery.zalithlauncher.viewmodel.SetupWizardViewModel
import com.movtery.zalithlauncher.viewmodel.sendToast
import java.io.File

/**
 * Star Launcher first-run setup wizard.
 *
 * Steps: Welcome → Account → Permissions → Hardware scan → GPU driver (Adreno) → Finish.
 * Shown automatically on first launch; can be re-run from launcher settings.
 */
@Composable
fun SetupWizardScreen(
    backStackViewModel: ScreenBackStackViewModel,
    eventViewModel: EventViewModel,
    submitError: (ErrorViewModel.ThrowableMessage) -> Unit,
    viewModel: SetupWizardViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val account by AccountsManager.currentAccountFlow.collectAsStateWithLifecycle()

    // bump this whenever the activity resumes so permission states refresh
    var resumeTick by remember { mutableIntStateOf(0) }

    // Re-run mode: settings invited the user back, start clean
    LaunchedEffect(Unit) {
        if (AllSettings.setupCompleted.state && !viewModel.completed) {
            viewModel.reset()
        }
    }

    // finish the wizard: apply + go home
    fun finishWizard() {
        viewModel.applySettings(context)
        backStackViewModel.mainScreen.clearWith(NormalNavKey.LauncherMain)
    }

    // observe Microsoft login completion (flag set when the user starts the flow)
    LaunchedEffect(account, viewModel.awaitingMicrosoftLogin) {
        if (viewModel.awaitingMicrosoftLogin && account?.isMicrosoftAccount() == true) {
            viewModel.awaitingMicrosoftLogin = false
            viewModel.nextStep()
        }
    }

    // refresh permission states and plugins when coming back from settings/installer
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (viewModel.installPromptShown) {
                    viewModel.rescanPlugins(context)
                    viewModel.installPromptShown = false
                }
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showDriverStep = viewModel.showDriverSelection() || viewModel.step == SetupStep.DRIVER
    val visibleSteps = if (showDriverStep) {
        SetupStep.entries
    } else {
        SetupStep.entries.filter { it != SetupStep.DRIVER }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        WizardHeader(
            step = viewModel.step,
            stepIndex = visibleSteps.indexOf(viewModel.step).coerceAtLeast(0),
            stepCount = visibleSteps.size
        )
        Spacer(Modifier.height(20.dp))

        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = viewModel.step,
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    val dir = if (forward) 1 else -1
                    (fadeIn(tween(250)) + slideInHorizontally(tween(250)) { dir * it / 6 }) togetherWith
                            (fadeOut(tween(200)) + slideOutHorizontally(tween(250)) { -dir * it / 6 })
                },
                label = "setup_step"
            ) { step ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (step) {
                        SetupStep.WELCOME -> WelcomeStep(
                            onGetStarted = { viewModel.nextStep() },
                            onSkip = {
                                viewModel.skipSetup(context)
                                backStackViewModel.mainScreen.clearWith(NormalNavKey.LauncherMain)
                            }
                        )

                        SetupStep.ACCOUNT -> AccountStep(
                            viewModel = viewModel,
                            account = account,
                            eventViewModel = eventViewModel,
                            submitError = submitError,
                            toWeb = { url ->
                                backStackViewModel.mainScreen.backStack.navigateToWeb(url)
                            },
                            backFromWeb = {
                                backStackViewModel.mainScreen.backStack.removeLastOrNull()
                            },
                            checkInWebScreen = {
                                backStackViewModel.mainScreen.currentKey is NormalNavKey.WebScreen
                            }
                        )

                        SetupStep.PERMISSIONS -> PermissionsStep(
                            activity = activity,
                            resumeTick = resumeTick
                        )

                        SetupStep.HARDWARE -> HardwareStep(viewModel = viewModel)

                        SetupStep.DRIVER -> DriverStep(
                            viewModel = viewModel,
                            eventViewModel = eventViewModel,
                            resumeTick = resumeTick
                        )

                        SetupStep.FINISH -> FinishStep(
                            viewModel = viewModel,
                            account = account
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        WizardBottomBar(
            step = viewModel.step,
            canGoNext = when (viewModel.step) {
                SetupStep.HARDWARE -> !viewModel.scanning
                SetupStep.DRIVER -> viewModel.downloadingEntry == null
                else -> true
            },
            onBack = { viewModel.previousStep() },
            onNext = {
                if (viewModel.step == SetupStep.FINISH) finishWizard() else viewModel.nextStep()
            }
        )
    }
}

/* --------------------------------------------------------------- header + bottom bar */

@Composable
private fun WizardHeader(step: SetupStep, stepIndex: Int, stepCount: Int) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.img_launcher),
                contentDescription = null,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.setup_welcome_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = stringResource(R.string.setup_header_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            progress = { (stepIndex + 1) / stepCount.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(step.titleRes()) + "  ·  ${stepIndex + 1}/$stepCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun SetupStep.titleRes(): Int = when (this) {
    SetupStep.WELCOME -> R.string.setup_step_welcome
    SetupStep.ACCOUNT -> R.string.setup_step_account
    SetupStep.PERMISSIONS -> R.string.setup_step_permissions
    SetupStep.HARDWARE -> R.string.setup_step_hardware
    SetupStep.DRIVER -> R.string.setup_step_driver
    SetupStep.FINISH -> R.string.setup_step_finish
}

@Composable
private fun WizardBottomBar(
    step: SetupStep,
    canGoNext: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (step != SetupStep.WELCOME) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.generic_back))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }

        ScalingActionButton(
            onClick = onNext,
            enabled = canGoNext,
            shape = ButtonDefaults.shape
        ) {
            Text(
                text = stringResource(
                    if (step == SetupStep.FINISH) R.string.setup_finish_start
                    else R.string.setup_next
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/* --------------------------------------------------------------- welcome */

@Composable
private fun WelcomeStep(onGetStarted: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.img_launcher),
            contentDescription = null,
            modifier = Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(28.dp))
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.setup_welcome_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.setup_welcome_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(32.dp))
        ScalingActionButton(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text(
                text = stringResource(R.string.setup_welcome_get_started),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onSkip) {
            Text(stringResource(R.string.setup_welcome_skip))
        }
    }
}

/* --------------------------------------------------------------- account */

@Composable
private fun AccountStep(
    viewModel: SetupWizardViewModel,
    account: Account?,
    eventViewModel: EventViewModel,
    submitError: (ErrorViewModel.ThrowableMessage) -> Unit,
    toWeb: (String) -> Unit,
    backFromWeb: () -> Unit,
    checkInWebScreen: () -> Boolean
) {
    val context = LocalContext.current
    var showLocalInput by remember { mutableStateOf(false) }
    var userName by remember(viewModel.localUserName) { mutableStateOf(viewModel.localUserName) }
    val msAvailable = BuildKeys.OAUTH_CLIENT_ID.isNotBlank()

    val nameError: String? = when {
        userName.isBlank() -> null
        userName.length < 3 -> stringResource(R.string.account_supporting_username_invalid_short)
        userName.length > 16 -> stringResource(R.string.account_supporting_username_invalid_long)
        Regex("[^a-zA-Z0-9_]").containsMatchIn(userName) -> stringResource(R.string.account_supporting_username_invalid_illegal_characters)
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.setup_account_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        account?.let { current ->
            InfoRow(
                iconRes = R.drawable.ic_person_outlined,
                title = stringResource(R.string.setup_account_logged_in, current.username),
                summary = current.accountType ?: ""
            )
        }

        // Microsoft login
        SelectableCard(
            selected = false,
            enabled = msAvailable && !isMicrosoftLogging(),
            onClick = {
                if (isMicrosoftLogging()) return@SelectableCard
                viewModel.awaitingMicrosoftLogin = true
                microsoftLogin(
                    context = context,
                    toWeb = toWeb,
                    backToMain = backFromWeb,
                    checkIfInWebScreen = checkInWebScreen,
                    updateOperation = { },
                    showToast = { text, duration -> eventViewModel.sendToast(text, duration) },
                    submitError = submitError,
                    onSuccess = {
                        viewModel.awaitingMicrosoftLogin = false
                        viewModel.nextStep()
                    }
                )
            },
            title = stringResource(R.string.setup_account_microsoft),
            summary = stringResource(R.string.setup_account_microsoft_desc),
            iconRes = R.drawable.ic_person_outlined
        )
        if (!msAvailable) {
            Text(
                text = stringResource(R.string.setup_account_microsoft_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        // Local account
        SelectableCard(
            selected = showLocalInput,
            enabled = true,
            onClick = { showLocalInput = !showLocalInput },
            title = stringResource(R.string.setup_account_local),
            summary = stringResource(R.string.setup_account_local_desc),
            iconRes = R.drawable.ic_videogame_asset_outlined
        )

        AnimatedVisibility(visible = showLocalInput, modifier = Modifier.animateContentSize()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OwnOutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setup_account_local_username)) },
                    supportingText = {
                        val error = nameError
                        if (error != null) Text(error)
                    },
                    isError = nameError != null,
                    singleLine = true
                )
                ScalingActionButton(
                    onClick = {
                        if (nameError == null && userName.isNotBlank()) {
                            viewModel.localUserName = userName
                            localLogin(userName, getUUIDFromUserName(userName).toString())
                            viewModel.nextStep()
                        }
                    },
                    enabled = userName.isNotBlank() && nameError == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.setup_account_local_create))
                }
            }
        }

        Text(
            text = stringResource(R.string.setup_account_skip),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* --------------------------------------------------------------- permissions */

@Composable
private fun PermissionsStep(activity: Activity?, resumeTick: Int) {
    val context = LocalContext.current

    val storageGranted = remember(resumeTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else hasStoragePermissions(context)
    }
    val notificationsApplicable = Build.VERSION.SDK_INT >= 33
    val notificationsGranted = remember(resumeTick) {
        if (!notificationsApplicable) true
        else context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.setup_permissions_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        InfoRow(
            iconRes = R.drawable.ic_folder_filled,
            title = stringResource(R.string.setup_permissions_storage),
            summary = stringResource(R.string.setup_permissions_storage_desc),
            trailing = {
                PermissionButton(
                    granted = storageGranted,
                    enabled = activity != null
                ) {
                    val act = activity ?: return@PermissionButton
                    checkStoragePermissions(
                        activity = act,
                        message = context.getString(R.string.setup_permissions_storage_request),
                        messageSdk30 = context.getString(R.string.setup_permissions_storage_request),
                    )
                }
            }
        )

        InfoRow(
            iconRes = R.drawable.ic_chat_info,
            title = stringResource(R.string.setup_permissions_notifications),
            summary = stringResource(R.string.setup_permissions_notifications_desc),
            trailing = {
                if (notificationsApplicable) {
                    PermissionButton(granted = notificationsGranted, enabled = true) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        )

        Text(
            text = stringResource(R.string.setup_permissions_optional),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionButton(granted: Boolean, enabled: Boolean, onRequest: () -> Unit) {
    if (granted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.setup_permissions_granted),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    } else {
        TextButton(onClick = onRequest, enabled = enabled) {
            Text(stringResource(R.string.setup_permissions_grant))
        }
    }
}

/* --------------------------------------------------------------- hardware */

@Composable
private fun HardwareStep(viewModel: SetupWizardViewModel) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.startScan(this, context)
    }

    if (viewModel.scanning || viewModel.gpuInfo == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.setup_hardware_scanning),
                style = MaterialTheme.typography.titleMedium
            )
        }
        return
    }

    val gpu = viewModel.gpuInfo!!
    val recommendedId = viewModel.recommendedRendererId() ?: NGGL4ESRenderer.getUniqueIdentifier()
    val recommendedName = viewModel.rendererById(recommendedId)?.getRendererName() ?: ""

    LaunchedEffect(recommendedId) {
        if (viewModel.selectedRendererId == null) {
            viewModel.selectedRendererId = recommendedId
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoRow(
            iconRes = R.drawable.ic_android_filled,
            title = stringResource(R.string.setup_hardware_gpu),
            summary = gpu.displayName
        )
        InfoRow(
            iconRes = R.drawable.ic_videogame_asset_outlined,
            title = stringResource(R.string.setup_hardware_soc),
            summary = viewModel.socName.ifBlank { stringResource(R.string.setup_hardware_unknown) }
        )
        if (viewModel.totalMemoryMB > 0) {
            InfoRow(
                iconRes = R.drawable.ic_dashboard_filled,
                title = stringResource(R.string.setup_hardware_ram),
                summary = formatFileSize(viewModel.totalMemoryMB)
            )
        }
        InfoRow(
            iconRes = R.drawable.ic_settings_filled,
            title = stringResource(R.string.setup_hardware_android),
            summary = viewModel.androidVersion
        )

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_star_filled),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.setup_hardware_recommend, recommendedName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = if (recommendedId == KopperZinkRenderer.getUniqueIdentifier()) {
                stringResource(R.string.setup_hardware_recommend_adreno)
            } else {
                stringResource(R.string.setup_hardware_recommend_system, gpu.displayName)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* --------------------------------------------------------------- driver */

@Composable
private fun DriverStep(
    viewModel: SetupWizardViewModel,
    eventViewModel: EventViewModel,
    resumeTick: Int
) {
    val context = LocalContext.current
    val gpu = viewModel.gpuInfo
    val isAdreno = gpu?.classified == GpuVendor.ADRENO

    // renderer choice
    val recommendedId = viewModel.recommendedRendererId() ?: NGGL4ESRenderer.getUniqueIdentifier()
    LaunchedEffect(recommendedId) {
        if (viewModel.selectedRendererId == null) {
            viewModel.selectedRendererId = recommendedId
        }
    }

    // installed (non-bundled) driver plugins
    val installedDrivers = remember(resumeTick) {
        DriverPluginManager.getDriverList().filter { !it.isLauncher }
    }

    // community feed expansion
    var feedExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(feedExpanded) {
        if (feedExpanded) viewModel.loadDriverFeed(this)
    }

    val selectedDriverId = viewModel.selectedDriverId

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.setup_driver_renderer_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ---- renderer
        RadioCard(
            selected = viewModel.selectedRendererId == KopperZinkRenderer.getUniqueIdentifier(),
            onClick = { viewModel.selectedRendererId = KopperZinkRenderer.getUniqueIdentifier() },
            modifier = Modifier.fillMaxWidth()
        ) {
            TitleAndSummary(
                title = KopperZinkRenderer.getRendererName(),
                summary = stringResource(R.string.setup_driver_zink_desc)
            )
        }
        RadioCard(
            selected = viewModel.selectedRendererId == NGGL4ESRenderer.getUniqueIdentifier(),
            onClick = { viewModel.selectedRendererId = NGGL4ESRenderer.getUniqueIdentifier() },
            modifier = Modifier.fillMaxWidth()
        ) {
            TitleAndSummary(
                title = NGGL4ESRenderer.getRendererName(),
                summary = stringResource(R.string.setup_driver_krypton_desc)
            )
        }

        if (isAdreno) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.setup_driver_title),
                style = MaterialTheme.typography.titleMedium
            )

            RadioCard(
                selected = selectedDriverId == SetupWizardViewModel.ALL_DRIVERS,
                onClick = { viewModel.selectedDriverId = SetupWizardViewModel.ALL_DRIVERS },
                modifier = Modifier.fillMaxWidth()
            ) {
                TitleAndSummary(
                    title = stringResource(R.string.setup_driver_bundled),
                    summary = stringResource(R.string.setup_driver_bundled_desc)
                )
            }

            installedDrivers.forEach { driver ->
                RadioCard(
                    selected = selectedDriverId == driver.id,
                    onClick = { viewModel.selectedDriverId = driver.id },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TitleAndSummary(
                        title = stringResource(R.string.setup_driver_installed, driver.name),
                        summary = driver.summary ?: driver.id
                    )
                }
            }

            RadioCard(
                selected = feedExpanded,
                onClick = {
                    feedExpanded = !feedExpanded
                    if (!feedExpanded) viewModel.selectedDriverId = SetupWizardViewModel.ALL_DRIVERS
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                TitleAndSummary(
                    title = stringResource(R.string.setup_driver_community),
                    summary = stringResource(R.string.setup_driver_community_desc)
                )
            }

            AnimatedVisibility(visible = feedExpanded, modifier = Modifier.animateContentSize()) {
                DriverFeedList(
                    viewModel = viewModel,
                    eventViewModel = eventViewModel
                )
            }

            TextButton(onClick = {
                eventViewModel.sendEvent(EventViewModel.Event.OpenLink(URL_GITHUB_DRIVER_PLUGINS))
            }) {
                Text(stringResource(R.string.setup_driver_open_page))
            }
        } else {
            Text(
                text = stringResource(R.string.setup_driver_system_note, gpu?.displayName ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DriverFeedList(
    viewModel: SetupWizardViewModel,
    eventViewModel: EventViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val state = viewModel.driverFeedState) {
            DriverFeedState.Idle,
            DriverFeedState.Loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }

            is DriverFeedState.Failed -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.setup_driver_feed_error, state.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { viewModel.retryDriverFeed(scope) }) {
                        Text(stringResource(R.string.generic_refresh))
                    }
                }
            }

            is DriverFeedState.Ready -> {
                state.entries.take(12).forEach { entry ->
                    val compatible = entry.isCompatibleWith()
                    val downloading = viewModel.downloadingEntry?.name == entry.name
                    DriverFeedRow(
                        entry = entry,
                        compatible = compatible,
                        downloading = downloading,
                        progress = if (downloading) viewModel.downloadProgress else 0f,
                        onClick = {
                            if (!compatible || viewModel.downloadingEntry != null) return@DriverFeedRow
                            viewModel.downloadAndInstall(
                                scope = scope,
                                context = context,
                                entry = entry,
                                onReadyToInstall = { file ->
                                    viewModel.installPromptShown = true
                                    openApkInstaller(context, file) { message ->
                                        eventViewModel.sendToast(
                                            androidText(R.string.setup_driver_install_failed, message),
                                            Toast.LENGTH_LONG
                                        )
                                    }
                                },
                                onError = { message ->
                                    eventViewModel.sendToast(
                                        androidText(R.string.setup_driver_download_failed, message),
                                        Toast.LENGTH_LONG
                                    )
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverFeedRow(
    entry: DriverFeedEntry,
    compatible: Boolean,
    downloading: Boolean,
    progress: Float,
    onClick: () -> Unit
) {
    val incompatibleText = if (!compatible && entry.minSdk != null) {
        stringResource(R.string.setup_driver_incompatible, "API ${entry.minSdk}")
    } else null

    Column {
        SelectableCard(
            selected = false,
            enabled = compatible && !downloading,
            onClick = onClick,
            title = entry.name.removeSuffix(".apk"),
            summary = buildString {
                entry.versionName?.let { append(it).append("  ·  ") }
                DriverFeed.formatDate(entry.publishedAt)?.let { append(it).append("  ·  ") }
                if (entry.sizeBytes > 0) append(formatFileSize(entry.sizeBytes))
                incompatibleText?.let { append("  ·  ").append(it) }
            },
            iconRes = R.drawable.ic_download_2_outlined
        )
        if (downloading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            )
        }
    }
}

private fun openApkInstaller(context: Context, file: File, onError: (String) -> Unit) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (t: Throwable) {
        onError(t.message ?: t::class.java.simpleName ?: "error")
    }
}

/* --------------------------------------------------------------- finish */

@Composable
private fun FinishStep(
    viewModel: SetupWizardViewModel,
    account: Account?
) {
    val rendererName = viewModel.rendererById(viewModel.selectedRendererId)?.getRendererName()
        ?: viewModel.rendererById(viewModel.recommendedRendererId())?.getRendererName() ?: ""
    val driverName = when (val id = viewModel.selectedDriverId) {
        SetupWizardViewModel.ALL_DRIVERS -> stringResource(R.string.setup_driver_bundled)
        else -> DriverPluginManager.getDriver(id).name
    }
    val ram = AllSettings.ramAllocation.state

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.setup_finish_title),
                style = MaterialTheme.typography.headlineSmall
            )
        }

        InfoRow(
            iconRes = R.drawable.ic_person_outlined,
            title = stringResource(R.string.setup_finish_account),
            summary = account?.username ?: stringResource(R.string.setup_finish_not_set)
        )
        InfoRow(
            iconRes = R.drawable.ic_video_settings,
            title = stringResource(R.string.setup_driver_renderer),
            summary = rendererName
        )
        if (viewModel.showDriverSelection()) {
            InfoRow(
                iconRes = R.drawable.ic_download_2_outlined,
                title = stringResource(R.string.setup_driver_title),
                summary = driverName
            )
        }
        if (ram != null) {
            InfoRow(
                iconRes = R.drawable.ic_dashboard_filled,
                title = stringResource(R.string.setup_finish_memory),
                summary = "$ram MB"
            )
        }

        Text(
            text = stringResource(R.string.setup_finish_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* --------------------------------------------------------------- shared bits */

@Composable
private fun InfoRow(
    iconRes: Int,
    title: String,
    summary: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SelectableCard(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    title: String,
    summary: String,
    iconRes: Int
) {
    RadioCard(
        selected = selected,
        onClick = if (enabled) onClick else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            TitleAndSummary(
                modifier = Modifier.weight(1f),
                title = title,
                summary = summary.takeIf { it.isNotBlank() }
            )
        }
    }
}
