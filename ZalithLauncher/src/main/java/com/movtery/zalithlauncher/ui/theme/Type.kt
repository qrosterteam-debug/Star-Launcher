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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.movtery.zalithlauncher.R

/**
 * Star Launcher display font: Orbitron (SIL OFL 1.1).
 * Applied to display / headline / title styles for the SL brand look;
 * body and label styles keep the platform default for readability.
 */
private val StarDisplayFont = FontFamily(
    Font(R.font.orbitron_extrabold, weight = FontWeight.ExtraBold),
    Font(R.font.orbitron_semibold, weight = FontWeight.SemiBold)
)

private val DefaultTypography = Typography()

val AppTypography = Typography(
    displayLarge = DefaultTypography.displayLarge.copy(fontFamily = StarDisplayFont, fontWeight = FontWeight.ExtraBold),
    displayMedium = DefaultTypography.displayMedium.copy(fontFamily = StarDisplayFont, fontWeight = FontWeight.ExtraBold),
    displaySmall = DefaultTypography.displaySmall.copy(fontFamily = StarDisplayFont, fontWeight = FontWeight.ExtraBold),
    headlineLarge = DefaultTypography.headlineLarge.copy(fontFamily = StarDisplayFont, fontWeight = FontWeight.ExtraBold),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontFamily = StarDisplayFont, fontWeight = FontWeight.ExtraBold),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontFamily = StarDisplayFont, fontWeight = FontWeight.ExtraBold),
    titleLarge = DefaultTypography.titleLarge.copy(fontFamily = StarDisplayFont, fontWeight = FontWeight.SemiBold),
    titleMedium = DefaultTypography.titleMedium.copy(fontFamily = StarDisplayFont, fontWeight = FontWeight.SemiBold),
    titleSmall = DefaultTypography.titleSmall.copy(fontFamily = StarDisplayFont, fontWeight = FontWeight.SemiBold)
)
