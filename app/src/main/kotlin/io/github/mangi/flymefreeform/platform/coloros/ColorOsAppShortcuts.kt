package io.github.mangi.flymefreeform.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.UserHandle
import androidx.core.graphics.createBitmap
import io.github.mangi.flymefreeform.apps.AppShortcutSource

internal data class AppShortcut(val packageName: String, val id: String, val label: String, val icon: Bitmap?, val user: UserHandle)

internal class ColorOsAppShortcuts(context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val iconSize = (24f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private val densityDpi = context.resources.displayMetrics.densityDpi

    fun forActivity(source: AppShortcutSource): List<AppShortcut> {
        val component = ComponentName(source.packageName, source.className)
        val user = UserHandle.getUserHandleForUid(source.userId * 100_000)
        val service = launcherApps ?: return emptyList()
        return try {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(component.packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                )
            service.getShortcuts(query, user)
                .orEmpty()
                .asSequence()
                .filter { it.userHandle == user && it.isEnabled && (it.activity == null || it.activity == component) }
                .sortedBy { it.rank }
                .distinctBy { it.id }
                .take(MAX_SHORTCUTS)
                .map { info ->
                    val icon = try {
                        service.getShortcutIconDrawable(info, densityDpi)?.let { drawable ->
                            createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888).also { bitmap ->
                                drawable.setBounds(0, 0, iconSize, iconSize)
                                drawable.draw(Canvas(bitmap))
                                bitmap.prepareToDraw()
                            }
                        }
                    } catch (_: RuntimeException) {
                        null
                    }
                    AppShortcut(info.`package`, info.id, info.shortLabel?.toString()?.takeIf { it.isNotBlank() } ?: info.id, icon, info.userHandle)
                }
                .toList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    companion object {
        const val MAX_SHORTCUTS = 4
    }
}
