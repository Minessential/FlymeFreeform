package io.github.mangi.flymefreeform.hook

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/** Hides the native sidebar affordance while keeping its resident service and provider alive. */
internal class SidebarHostHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var inputUtils: Class<*>? = null
    private var setInputArea: java.lang.reflect.Method? = null
    private var registerInput: java.lang.reflect.Method? = null
    private var unregisterInput: java.lang.reflect.Method? = null
    private var updateVisibility: java.lang.reflect.Method? = null
    private var appliedSuppression: Boolean? = null

    fun install(loader: ClassLoader) {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            val floatBarClass = loader.loadClass(FLOAT_BAR_CLASS)
            val inputClass = loader.loadClass(INPUT_UTILS_CLASS)
            inputUtils = inputClass
            val visibility = floatBarClass.getDeclaredMethod(
                "updateFloatBarVisibility",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val setArea = inputClass.getDeclaredMethod(
                "s",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val register = inputClass.getDeclaredMethod("m").apply { isAccessible = true }
            val unregister = inputClass.getDeclaredMethod("q").apply { isAccessible = true }
            updateVisibility = visibility
            setInputArea = setArea
            registerInput = register
            unregisterInput = unregister

            handles += module.hook(visibility)
                .setId("flymefreeform.sidebar_host.float_bar_visibility")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (suppressionEnabled() && chain.getArg(0) == true) {
                        chain.proceed(arrayOf<Any?>(false, false, false, 0))
                    } else {
                        chain.proceed()
                    }
                }
            handles += module.hook(setArea)
                .setId("flymefreeform.sidebar_host.input_area")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (suppressionEnabled()) chain.proceed(arrayOf<Any?>(0, 0, 0, 0)) else chain.proceed()
                }
            handles += module.hook(register)
                .setId("flymefreeform.sidebar_host.input_register")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> if (suppressionEnabled()) null else chain.proceed() }

            configuration.observe { settings ->
                val shouldSuppress = configuration.isAvailable && settings.enabled && settings.hideSystemSidebarUi
                val previous = appliedSuppression
                appliedSuppression = shouldSuppress
                mainHandler.post {
                    when {
                        shouldSuppress -> suppressCurrentState()
                        previous == true -> restoreCurrentState()
                    }
                }
            }
            module.log(Log.INFO, TAG, "SIDEBAR_HOST_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            handles.forEach { it.unhook() }
            module.log(Log.WARN, TAG, "SIDEBAR_HOST_SIGNATURE_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            handles.forEach { it.unhook() }
            module.log(Log.WARN, TAG, "SIDEBAR_HOST_HOOK_FAILED", exception)
        } catch (error: LinkageError) {
            handles.forEach { it.unhook() }
            module.log(Log.WARN, TAG, "SIDEBAR_HOST_LINKAGE_FAILED", error)
        }
    }

    private fun suppressionEnabled(): Boolean =
        configuration.isAvailable && configuration.snapshot.enabled && configuration.snapshot.hideSystemSidebarUi

    private fun suppressCurrentState() {
        safely("SIDEBAR_HOST_CLEANUP_FAILED") {
            unregisterInput?.invoke(null)
            setInputArea?.invoke(null, 0, 0, 0, 0)
            val floatBar = currentFloatBar() ?: return@safely
            updateVisibility?.invoke(floatBar, false, false, false, 0)
        }
    }

    private fun restoreCurrentState() {
        safely("SIDEBAR_HOST_RESTORE_FAILED") {
            setInputArea?.invoke(null, 0, 0, 0, 0)
            registerInput?.invoke(null)
            val floatBar = currentFloatBar() ?: return@safely
            updateVisibility?.invoke(floatBar, true, false, false, 0)
        }
    }

    private fun currentFloatBar(): Any? {
        val managerClass = inputUtils?.classLoader?.loadClass(DISPLAY_DATA_MANAGER_CLASS) ?: return null
        val singleton = managerClass.getDeclaredField("a").apply { isAccessible = true }.get(null) ?: return null
        return managerClass.getDeclaredMethod("k").apply { isAccessible = true }.invoke(singleton)
    }

    private inline fun safely(code: String, action: () -> Unit) {
        try {
            action()
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, code, exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, code, exception)
        } catch (error: LinkageError) {
            module.log(Log.WARN, TAG, code, error)
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val FLOAT_BAR_CLASS = "com.oplus.smartsidebar.permanent.floatbar.FloatBarMainView"
        const val INPUT_UTILS_CLASS = "com.oplus.smartsidebar.utils.InputUtils"
        const val DISPLAY_DATA_MANAGER_CLASS = "com.oplus.smartsidebar.permanent.models.DisplayDataManager"
    }
}
