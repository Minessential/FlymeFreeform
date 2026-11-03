package io.github.mangi.flymefreeform.apps

import io.github.mangi.flymefreeform.config.PinnedTarget

/** 快捷操作归属于具体入口和用户；Sidebar.userId 是目录所有者，不能当作分身用户。 */
internal data class AppShortcutSource(val packageName: String, val className: String, val userId: Int) {
    companion object {
        fun forClone(target: PinnedTarget.Sidebar, packageName: String, className: String?, userId: Int): AppShortcutSource? {
            if (target.kind != PinnedTarget.Kind.CloneApp || userId !in 1..21_474 || userId == target.userId ||
                packageName.isBlank() || packageName.length > 256 || className.isNullOrBlank() || className.length > 512
            ) return null
            return AppShortcutSource(packageName, className, userId)
        }
    }
}
