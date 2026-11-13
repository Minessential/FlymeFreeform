package io.github.mangi.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import java.util.concurrent.atomic.AtomicInteger

/** 每次提交都重新校验组件，并仅携带 ColorOS 自由窗参数启动，不退化为普通全屏启动。 */
internal class ColorOsFreeformLauncher(
    private val context: Context,
) {
    private val pendingRequestCode = AtomicInteger(1)

    fun launch(component: ComponentName): FreeformLaunchResult {
        if (!isLaunchable(component)) return FreeformLaunchResult.TargetUnavailable

        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = createLaunchOptions()

        return try {
            context.startActivity(intent, options)
            FreeformLaunchResult.Started
        } catch (exception: SecurityException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_SECURITY_REJECTED", exception)
        } catch (exception: RuntimeException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_START_FAILED", exception)
        }
    }

    /**
     * 分身应用必须由 system_server 以目标用户身份启动。
     * 直接 startActivity 只会使用 Binder 调用者所在的用户（system_server 为 0），
     * 必须用指定用户的 PendingIntent；同时扇形菜单阶段 Smart Sidebar 的 :ui 进程没有可见窗口，
     * Android 16 的 BAL 会拦截它转发的小窗启动，原生路由仍返回成功，表现为静默失败。
     */
    fun launch(component: ComponentName, userId: Int): FreeformLaunchResult {
        if (userId !in 1..MAX_USER_ID) return FreeformLaunchResult.TargetUnavailable
        val user = UserHandle.getUserHandleForUid(userId * 100_000)
        val userContext = contextAsUser(userId)
            ?: return FreeformLaunchResult.Failed(
                "FREEFORM_CLONE_USER_CONTEXT_UNAVAILABLE",
                IllegalStateException("userId=$userId"),
            )
        if (!isLaunchable(userContext, component)) return FreeformLaunchResult.TargetUnavailable

        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                // 与侧边栏原生路由一致：从图标启动时回到任务根，再套用自由窗参数。
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val createOptions = createLaunchOptions()
        // BAL 模式只能出现在 send 的 options 里；创建 PendingIntent 时带上会被系统直接拒绝。
        val sendOptions = createShortcutLaunchOptions()
        val pendingIntent = try {
            createUserPendingIntent(intent, createOptions, user)
        } catch (exception: RuntimeException) {
            return FreeformLaunchResult.Failed("FREEFORM_CLONE_PENDING_INTENT_FAILED", exception)
        }

        return try {
            sendWithOptions(pendingIntent, sendOptions)
            FreeformLaunchResult.Started
        } catch (exception: PendingIntent.CanceledException) {
            FreeformLaunchResult.Failed("FREEFORM_CLONE_PENDING_INTENT_CANCELED", exception)
        } catch (exception: SecurityException) {
            FreeformLaunchResult.Failed("FREEFORM_CLONE_LAUNCH_SECURITY_REJECTED", exception)
        } catch (exception: RuntimeException) {
            FreeformLaunchResult.Failed("FREEFORM_CLONE_LAUNCH_START_FAILED", exception)
        } finally {
            pendingIntent.cancel()
        }
    }

    fun launch(shortcut: AppShortcut): FreeformLaunchResult {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
            ?: return FreeformLaunchResult.TargetUnavailable
        return try {
            launcherApps.startShortcut(shortcut.packageName, shortcut.id, null, createShortcutLaunchOptions(), shortcut.user)
            FreeformLaunchResult.Started
        } catch (exception: SecurityException) {
            FreeformLaunchResult.Failed("FREEFORM_SHORTCUT_SECURITY_REJECTED", exception)
        } catch (exception: RuntimeException) {
            FreeformLaunchResult.Failed("FREEFORM_SHORTCUT_START_FAILED", exception)
        }
    }

    private fun isLaunchable(component: ComponentName): Boolean = isLaunchable(context, component)

    private fun isLaunchable(source: Context, component: ComponentName): Boolean =
        try {
            source.packageManager
                .getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
                .let { info -> info.enabled && info.applicationInfo.enabled && info.exported }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    /** system_server 持有 INTERACT_ACROSS_USERS；这里只为目标用户建立启动上下文。 */
    private fun contextAsUser(userId: Int): Context? =
        try {
            Context::class.java
                .getMethod("createContextAsUser", android.os.UserHandle::class.java, Int::class.javaPrimitiveType)
                .invoke(context, android.os.UserHandle.getUserHandleForUid(userId * 100_000), 0) as? Context
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** 公开 SDK 没有 getActivityAsUser；必须显式带用户，PendingIntent 才会落在分身用户。 */
    private fun createUserPendingIntent(intent: Intent, options: Bundle, user: UserHandle): PendingIntent =
        try {
            PendingIntent::class.java
                .getMethod(
                    "getActivityAsUser",
                    Context::class.java,
                    Int::class.javaPrimitiveType,
                    Intent::class.java,
                    Int::class.javaPrimitiveType,
                    Bundle::class.java,
                    UserHandle::class.java,
                )
                .invoke(
                    null,
                    context,
                    pendingRequestCode.incrementAndGet(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    options,
                    user,
                ) as PendingIntent
        } catch (exception: java.lang.reflect.InvocationTargetException) {
            throw exception.cause as? RuntimeException
                ?: IllegalStateException("getActivityAsUser", exception.cause)
        } catch (exception: ReflectiveOperationException) {
            throw IllegalStateException("getActivityAsUser", exception)
        }

    /** 带 ActivityOptions 的 send 同样是隐藏 API；窗口与 BAL 参数必须随发送一起交给系统。 */
    private fun sendWithOptions(pendingIntent: PendingIntent, options: Bundle) {
        try {
            PendingIntent::class.java
                .getMethod("send", Bundle::class.java)
                .invoke(pendingIntent, options)
        } catch (exception: java.lang.reflect.InvocationTargetException) {
            when (val cause = exception.cause) {
                is PendingIntent.CanceledException -> throw cause
                is RuntimeException -> throw cause
                else -> throw IllegalStateException("PendingIntent.send", cause)
            }
        } catch (exception: ReflectiveOperationException) {
            throw IllegalStateException("PendingIntent.send", exception)
        }
    }

    private fun createLaunchOptions(options: ActivityOptions = ActivityOptions.makeBasic()): Bundle {
        applyWindowingMode(options)
        // 厂商字段最后写入，避免 ActivityOptions 的默认值覆盖小窗参数。
        return options.toBundle().apply {
            putInt(ZOOM_FLAGS_KEY, ZOOM_LAUNCH_FLAG)
            putInt(WINDOWING_MODE_KEY, FLEXIBLE_WINDOWING_MODE)
        }
    }

    /** 仅用于已通过环境检查的用户手势提交，不为普通后台调用授予启动能力。 */
    @Suppress("DEPRECATION")
    private fun createShortcutLaunchOptions(): Bundle {
        // 快捷方式与分身应用都以 PendingIntent 投递，system_server 是 realCaller。
        // Android 16 的旧 ALLOWED 模式不包含 realCaller 的系统 UID/BAL 权限豁免；
        // 目标进程不存在时必须显式 ALLOW_ALWAYS，否则会返回 BAL_BLOCK。
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        return createLaunchOptions(
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode),
        )
    }

    /** 隐藏 setter 只是补强；ColorOS 的两个 Bundle 参数才是不可缺少的启动协议。 */
    @SuppressLint("BlockedPrivateApi")
    private fun applyWindowingMode(options: ActivityOptions) {
        try {
            ActivityOptions::class.java
                .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
                .invoke(options, FLEXIBLE_WINDOWING_MODE)
        } catch (_: ReflectiveOperationException) {
            // 保留同一个 options 上的公开 BAL 设置，继续使用 ColorOS Bundle 协议。
        } catch (_: RuntimeException) {
            // 隐藏 API 不可用时不丢弃公开参数。
        }
    }

    private companion object {
        const val MAX_USER_ID = 21_474
        const val FLEXIBLE_WINDOWING_MODE = 100
        const val ZOOM_LAUNCH_FLAG = 4
        const val WINDOWING_MODE_KEY = "android.activity.windowingMode"
        const val ZOOM_FLAGS_KEY = "android:activity.mZoomLaunchFlags"
    }
}

internal sealed interface FreeformLaunchResult {
    data object Started : FreeformLaunchResult

    data object TargetUnavailable : FreeformLaunchResult

    data class Failed(
        val diagnosticCode: String,
        val cause: Throwable,
    ) : FreeformLaunchResult
}
