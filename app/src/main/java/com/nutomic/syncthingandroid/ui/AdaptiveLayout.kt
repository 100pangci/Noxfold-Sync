package com.nutomic.syncthingandroid.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Width at which the app switches from the phone navigation pattern to a rail. */
internal const val TABLET_MIN_WIDTH_DP = 600

private val TABLET_CONTENT_MAX_WIDTH = 1120.dp

/** Keeps long tablet layouts readable while leaving phone layouts unchanged. */
internal fun Modifier.contentWidthForTablet(isTablet: Boolean): Modifier =
    if (isTablet) widthIn(max = TABLET_CONTENT_MAX_WIDTH) else this
