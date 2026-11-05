package io.github.mangi.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.mangi.flymefreeform.apps.SelectableEntry
import io.github.mangi.flymefreeform.config.RadialMenuSettings
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import io.github.mangi.flymefreeform.config.PinnedTarget
import io.github.mangi.flymefreeform.framework.FrameworkConnectionState
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack

@Serializable
internal sealed interface AppRoute : NavKey {
    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object PinnedApps : AppRoute
}

@Composable
internal fun FlymeFreeformNavHost(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    sidebarCatalog: io.github.mangi.flymefreeform.apps.SidebarCatalogState,
    onRefreshCatalog: () -> Unit,
    onCancelCatalog: () -> Unit,
    onModuleEnabledChange: (Boolean) -> Unit,
    onHideSystemSidebarUiChange: (Boolean) -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onShortcutHoverDelayChange: (Int) -> Unit,
    onRadialMenuChange: (RadialMenuSettings) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onPreventMistouchInLandscapeChange: (Boolean) -> Unit,
    onPreventMistouchInGameModeChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
    onPinnedTargetsChange: (List<PinnedTarget>) -> Unit,
) {
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Settings)
    val popBackStack = remember(backStack) {
        {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            Unit
        }
    }
    val navigateToPinnedApps = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.PinnedApps) {
                backStack.add(AppRoute.PinnedApps)
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = popBackStack,
    ) {
        entry<AppRoute.Settings> {
            ControlScreen(
                state = state,
                onModuleEnabledChange = onModuleEnabledChange,
                onHideSystemSidebarUiChange = onHideSystemSidebarUiChange,
                onCornerSidesChange = onCornerSidesChange,
                onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                onShortcutHoverDelayChange = onShortcutHoverDelayChange,
                onRadialMenuChange = onRadialMenuChange,
                onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                onHandleSwipeUpToMiniEnabledChange =
                    onHandleSwipeUpToMiniEnabledChange,
                onPauseInLandscapeChange = onPauseInLandscapeChange,
                onPauseInGameModeChange = onPauseInGameModeChange,
                onPreventMistouchInLandscapeChange = onPreventMistouchInLandscapeChange,
                onPreventMistouchInGameModeChange = onPreventMistouchInGameModeChange,
                onRequestScopes = onRequestScopes,
                onNavigateToPinnedApps = navigateToPinnedApps,
            )
        }
        entry<AppRoute.PinnedApps> {
            PinnedAppsScreen(
                state = state,
                apps = apps,
                sidebarCatalog = sidebarCatalog,
                onRefreshCatalog = onRefreshCatalog,
                onCancelCatalog = onCancelCatalog,
                onBack = popBackStack,
                onPinnedTargetsChange = onPinnedTargetsChange,
            )
        }
    }
}
