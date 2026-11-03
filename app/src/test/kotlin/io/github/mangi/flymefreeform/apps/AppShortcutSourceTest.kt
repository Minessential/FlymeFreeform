package io.github.mangi.flymefreeform.apps

import io.github.mangi.flymefreeform.config.PinnedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppShortcutSourceTest {
    private val clone = PinnedTarget.Sidebar(PinnedTarget.Kind.CloneApp, "clone", 0)
    private val pkg = "com.example.app"
    private val activity = "$pkg.MainActivity"

    @Test
    fun clonesOfSameComponentKeepSeparateShortcutUsers() {
        val first = AppShortcutSource.forClone(clone, pkg, activity, 998)
        val second = AppShortcutSource.forClone(clone.copy(alias = "other"), pkg, activity, 999)
        assertEquals(AppShortcutSource(pkg, activity, 998), first)
        assertEquals(AppShortcutSource(pkg, activity, 999), second)
        assertNotEquals(first, second)
    }

    @Test
    fun directoryOwnerIsNeverUsedAsCloneShortcutUser() {
        assertNull(AppShortcutSource.forClone(clone, pkg, activity, 0))
        assertNull(AppShortcutSource.forClone(clone.copy(userId = 10), pkg, activity, 10))
        assertEquals(999, AppShortcutSource.forClone(clone.copy(userId = 10), pkg, activity, 999)?.userId)
    }

    @Test
    fun toolsAndSidebarShortcutsCannotSupplyCloneSources() {
        for (kind in listOf(PinnedTarget.Kind.Tool, PinnedTarget.Kind.Shortcut, PinnedTarget.Kind.AppEntry)) {
            assertNull(AppShortcutSource.forClone(clone.copy(kind = kind), pkg, activity, 999))
        }
    }

    @Test
    fun incompleteOrOutOfRangeMetadataCannotQueryAnotherUser() {
        for (user in listOf(-1, 21_475, Int.MAX_VALUE)) {
            assertNull(AppShortcutSource.forClone(clone, pkg, activity, user))
        }
        for (name in listOf(null, "", " ", "a".repeat(513))) {
            assertNull(AppShortcutSource.forClone(clone, pkg, name, 999))
        }
        assertNull(AppShortcutSource.forClone(clone, "", activity, 999))
        assertNull(AppShortcutSource.forClone(clone, "a".repeat(257), activity, 999))
    }
}
