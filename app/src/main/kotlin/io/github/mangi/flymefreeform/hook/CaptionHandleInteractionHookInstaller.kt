package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.hypot

/** Adds press feedback and double-tap close to the visible ordinary freeform bottom handle. */
@SuppressLint("PrivateApi")
internal class CaptionHandleInteractionHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private var lastFailureLogAt = -FAILURE_LOG_INTERVAL_MS

    fun install(classLoader: ClassLoader) {
        try {
            val captionClass = classLoader.loadClass(CAPTION_CLASS)
            val baseClass = classLoader.loadClass(BASE_CAPTION_CLASS)
            val access = CaptionAccess(captionClass, classLoader)
            if (!access.canSendBack) {
                module.log(Log.WARN, TAG, "CAPTION_HANDLE_BACK_UNAVAILABLE")
            }
            configuration.observe { settings ->
                if (!settings.enabled) access.resetAll()
            }

            hook(baseClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java), TOUCH_HOOK_ID) { chain ->
                val caption = chain.thisObject
                val event = chain.getArg(0) as? MotionEvent
                if (caption != null && captionClass.isInstance(caption) && event != null) {
                    try {
                        access.onTouch(caption, event, configuration)
                    } catch (exception: ReflectiveOperationException) {
                        access.reset(caption)
                        logFailure("CAPTION_HANDLE_TOUCH_FAILED", exception)
                    } catch (exception: RuntimeException) {
                        access.reset(caption)
                        logFailure("CAPTION_HANDLE_TOUCH_FAILED", exception)
                    }
                }
                chain.proceed()
            }

            hook(captionClass.getDeclaredMethod("onDoubleTap", MotionEvent::class.java), DOUBLE_TAP_HOOK_ID) { chain ->
                val original = chain.proceed() as? Boolean ?: false
                if (original) return@hook true
                val caption = chain.thisObject ?: return@hook original
                val event = chain.getArg(0) as? MotionEvent ?: return@hook original
                try {
                    access.armClose(caption, event, configuration)
                } catch (exception: ReflectiveOperationException) {
                    logFailure("CAPTION_HANDLE_DOUBLE_TAP_FAILED", exception)
                } catch (exception: RuntimeException) {
                    logFailure("CAPTION_HANDLE_DOUBLE_TAP_FAILED", exception)
                }
                original
            }

            hook(captionClass.getDeclaredMethod("onSingleTapConfirmed", MotionEvent::class.java), SINGLE_TAP_HOOK_ID) { chain ->
                val original = chain.proceed() as? Boolean ?: false
                if (original) return@hook true
                val caption = chain.thisObject ?: return@hook original
                val event = chain.getArg(0) as? MotionEvent ?: return@hook original
                try {
                    if (access.sendBack(caption, event, configuration)) return@hook true
                } catch (exception: ReflectiveOperationException) {
                    logFailure("CAPTION_HANDLE_BACK_FAILED", exception)
                } catch (exception: RuntimeException) {
                    logFailure("CAPTION_HANDLE_BACK_FAILED", exception)
                }
                original
            }

            hook(captionClass.getDeclaredMethod("onDoubleTapEvent", MotionEvent::class.java), DOUBLE_TAP_EVENT_HOOK_ID) { chain ->
                val caption = chain.thisObject ?: return@hook chain.proceed()
                val event = chain.getArg(0) as? MotionEvent ?: return@hook chain.proceed()
                try {
                    if (access.submitClose(caption, event, configuration)) return@hook true
                } catch (exception: ReflectiveOperationException) {
                    logFailure("CAPTION_HANDLE_DOUBLE_TAP_FAILED", exception)
                } catch (exception: RuntimeException) {
                    logFailure("CAPTION_HANDLE_DOUBLE_TAP_FAILED", exception)
                }
                chain.proceed()
            }

            val releaseMethod = try {
                captionClass.requiredCaptionMethod("onCaptionViewRelease", 0)
            } catch (_: NoSuchMethodException) {
                null
            }
            releaseMethod?.let { method ->
                hook(method, RELEASE_HOOK_ID) { chain ->
                    val caption = chain.thisObject
                    if (caption != null) access.cleanup(caption)
                    chain.proceed()
                }
            }

            module.log(Log.INFO, TAG, "CAPTION_HANDLE_HOOKS_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "CAPTION_HANDLE_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "CAPTION_HANDLE_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun hook(method: Method, id: String, callback: (XposedInterface.Chain) -> Any?) {
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId(id)
            .intercept(callback)
    }

    private fun logFailure(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogAt = now
        module.log(Log.WARN, TAG, code, throwable)
    }

    private class CaptionAccess(captionClass: Class<*>, classLoader: ClassLoader) {
        private val controllerField = captionClass.requiredCaptionField("mFlexibleTaskController")
        private val taskField = captionClass.requiredCaptionField("mTask")
        private val taskIdField: Field? = try {
            taskField.type.requiredCaptionField("mTaskId")
        } catch (_: ReflectiveOperationException) {
            null
        }
        private val bottomFrameField = captionClass.requiredCaptionField("mBottomFrame")
        private val handleField = captionClass.requiredCaptionField("mBottomHandleView")
        private val isDefaultZoomState = captionClass.requiredCaptionMethod("isDefaultZoomState", 0)
        private val isBottomFrameVisible = captionClass.requiredCaptionMethod("isBottomFrameVisible", 0)
        private val getActionFlag = captionClass.requiredCaptionMethod("getFlexibleTaskActionFlag", 2)
        private val controllerClass = classLoader.loadClass(CONTROLLER_CLASS)
        private val setFocusedTaskAndReSendBackKey: Method? = try {
            controllerClass.getDeclaredMethod("setFocusedTaskAndReSendBackKey", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
        } catch (_: ReflectiveOperationException) {
            null
        }
        private val isTaskInFlexibleState = controllerClass.requiredCaptionMethod("isTaskInFlexibleState", 2)
        private val getTopZoomTask = controllerClass.requiredCaptionMethod("getTopZoomTask", 0)
        private val exitFlexibleTask = controllerClass.requiredCaptionMethod("exitFlexibleTask", 4)
        private val animationHelperField = try {
            captionClass.requiredCaptionField("mAnimationHelper")
        } catch (_: ReflectiveOperationException) {
            null
        }
        private val springAccess: SpringAccess? = try {
            val helper = animationHelperField?.type ?: throw NoSuchFieldException("mAnimationHelper")
            val getSpring = helper.requiredCaptionMethod("getBottomHandleSpringAnim", 0)
            SpringAccess(getSpring, getSpring.returnType.requiredCaptionMethod("isRunning", 0))
        } catch (_: ReflectiveOperationException) {
            null
        }
        private val toggleAnimationMethod: Method? = try {
            classLoader.loadClass(FLEXIBLE_WINDOW_UTILS_CLASS)
                .requiredCaptionMethod("isFlexibleTaskToggleHandleInfoAnimating", 2)
        } catch (_: ReflectiveOperationException) {
            null
        }
        private val states = WeakHashMap<Any, PressState>()

        val canSendBack: Boolean
            get() = taskIdField != null && setFocusedTaskAndReSendBackKey != null

        fun onTouch(caption: Any, event: MotionEvent, configuration: ProcessConfiguration) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val state = synchronized(states) { states.getOrPut(caption) { PressState() } }
                    if (state.lastUpAt > 0 && event.eventTime - state.lastUpAt > ViewConfiguration.getDoubleTapTimeout()) state.lastUpWasBar = false
                    state.downX = event.x
                    state.downY = event.y
                    state.currentDownWasBar = isAllowed(caption, event.x, event.y, event, configuration)
                    state.moved = false
                    state.pressed = false
                    if (state.currentDownWasBar && !isSystemAnimating(caption)) startPress(caption, state)
                }
                MotionEvent.ACTION_MOVE -> {
                    val state = state(caption) ?: return
                    if (event.pointerCount != 1 || movedBeyondSlop(caption, state, event)) {
                        state.moved = true
                        state.pendingTask = null
                        state.lastUpWasBar = false
                        release(caption, state)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val state = state(caption) ?: return
                    val wasBar = state.currentDownWasBar && !state.moved && !movedBeyondSlop(caption, state, event)
                    release(caption, state)
                    state.lastUpWasBar = wasBar
                    state.lastUpAt = event.eventTime
                    state.currentDownWasBar = false
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> reset(caption)
            }
        }

        fun armClose(caption: Any, event: MotionEvent, configuration: ProcessConfiguration) {
            val state = state(caption) ?: return
            if (state.lastUpWasBar && state.currentDownWasBar &&
                isAllowed(caption, event.x, event.y, event, configuration)
            ) {
                state.pendingTask = taskField.get(caption)
            }
        }

        fun submitClose(caption: Any, event: MotionEvent, configuration: ProcessConfiguration): Boolean {
            val state = state(caption) ?: return false
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                state.pendingTask = null
                return false
            }
            if (event.actionMasked != MotionEvent.ACTION_UP) return false
            val task = state.pendingTask ?: return false
            state.pendingTask = null
            val x = event.x
            val y = event.y
            (caption as? View)?.post {
                try {
                    closeIfValid(caption, task, x, y, configuration)
                } catch (_: ReflectiveOperationException) {
                    // A disappearing OEM task/view is a fail-open condition.
                } catch (_: RuntimeException) {
                    // A disappearing OEM task/view is a fail-open condition.
                }
            }
            return true
        }

        fun sendBack(caption: Any, event: MotionEvent, configuration: ProcessConfiguration): Boolean {
            val state = state(caption) ?: return false
            if (!state.lastUpWasBar || !isAllowed(caption, event.x, event.y, event, configuration)) return false
            val task = taskField.get(caption) ?: return false
            val controller = controllerField.get(caption) ?: return false
            val taskId = taskIdField?.getInt(task) ?: return false
            val method = setFocusedTaskAndReSendBackKey ?: return false
            val view = caption as? View ?: return false
            val downX = event.x
            val downY = event.y
            val posted = view.post {
                try {
                    if (!configuration.isAvailable || !configuration.snapshot.enabled) return@post
                    if (taskField.get(caption) !== task || getTopZoomTask.invokeCaption(controller) !== task) return@post
                    if (isTaskInFlexibleState.invokeCaption(controller, task, ORDINARY_STATE) != true) return@post
                    if (!isOrdinary(caption) || !isHandleVisible(caption) ||
                        actionFlag(caption, downX, downY) != BOTTOM_HANDLE_ACTION
                    ) return@post
                    method.invokeCaption(controller, taskId)
                } catch (_: ReflectiveOperationException) {
                    // A disappearing OEM task/view is a fail-open condition.
                } catch (_: RuntimeException) {
                    // A disappearing OEM task/view is a fail-open condition.
                }
            }
            return posted
        }

        fun reset(caption: Any) {
            val state = state(caption) ?: return
            release(caption, state)
            state.currentDownWasBar = false
            state.lastUpWasBar = false
            state.pendingTask = null
        }

        fun cleanup(caption: Any) {
            reset(caption)
            synchronized(states) { states.remove(caption) }
        }

        fun resetAll() {
            val captions = synchronized(states) { states.keys.toList() }
            captions.forEach(::reset)
        }

        private fun closeIfValid(caption: Any, task: Any, x: Float, y: Float, configuration: ProcessConfiguration) {
            if (!configuration.isAvailable || !configuration.snapshot.enabled) return
            val controller = controllerField.get(caption) ?: return
            if (taskField.get(caption) !== task || getTopZoomTask.invokeCaption(controller) !== task) return
            if (isTaskInFlexibleState.invokeCaption(controller, task, ORDINARY_STATE) != true) return
            if (!isHandleVisible(caption) || actionFlag(caption, x, y) != BOTTOM_HANDLE_ACTION) return
            exitFlexibleTask.invokeCaption(controller, task, true, 0, 0)
        }

        private fun isAllowed(caption: Any, x: Float, y: Float, event: MotionEvent, configuration: ProcessConfiguration): Boolean {
            if (!configuration.isAvailable || !configuration.snapshot.enabled) return false
            if (event.pointerCount != 1 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER ||
                !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
            ) return false
            if (!isOrdinary(caption) || !isHandleVisible(caption)) return false
            val handle = handleField.get(caption) as? View ?: return false
            if (handle.visibility != View.VISIBLE || handle.alpha <= 0f) return false
            return actionFlag(caption, x, y) == BOTTOM_HANDLE_ACTION
        }

        private fun isOrdinary(caption: Any): Boolean = isDefaultZoomState.invokeCaption(caption) == true

        private fun isHandleVisible(caption: Any): Boolean =
            isBottomFrameVisible.invokeCaption(caption) == true &&
                (bottomFrameField.get(caption) as? View)?.visibility == View.VISIBLE

        private fun actionFlag(caption: Any, x: Float, y: Float): Int =
            (getActionFlag.invokeCaption(caption, x.toInt(), y.toInt()) as? Number)?.toInt() ?: -1

        private fun isSystemAnimating(caption: Any): Boolean {
            val task = taskField.get(caption)
            val toggleMethod = toggleAnimationMethod
            if (task != null && toggleMethod != null && toggleMethod.invokeCaption(null, task, HANDLE_ANIMATION_ID) == true) return true
            val access = springAccess ?: return false
            val helper = animationHelperField?.get(caption) ?: return false
            val spring = access.getSpring.invokeCaption(helper) ?: return false
            return access.isRunning.invokeCaption(spring) == true
        }

        private fun startPress(caption: Any, state: PressState) {
            val bar = handleField.get(caption) as? View ?: return
            if (state.hasBaseline) {
                bar.animate().cancel()
                bar.scaleX = state.baseScaleX
                bar.scaleY = state.baseScaleY
                bar.translationY = state.baseTranslationY
            } else {
                state.baseScaleX = bar.scaleX
                state.baseScaleY = bar.scaleY
                state.baseTranslationY = bar.translationY
                state.hasBaseline = true
            }
            val context = bar.context
            state.pressed = true
            bar.animate().cancel()
            bar.animate().scaleX(state.baseScaleX * PRESS_SCALE_X).scaleY(state.baseScaleY * PRESS_SCALE_Y)
                .translationY(state.baseTranslationY + 0.5f * context.resources.displayMetrics.density)
                .setDuration(PRESS_IN_DURATION_MS).start()
            state.longPressRunnable = Runnable {
                if (state.pressed) {
                    state.moved = true
                    release(caption, state)
                }
            }.also { bar.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
        }

        private fun release(caption: Any, state: PressState) {
            val bar = handleField.get(caption) as? View
            state.longPressRunnable?.let { bar?.removeCallbacks(it) }
            state.longPressRunnable = null
            if (!state.pressed || bar == null) return
            state.pressed = false
            bar.animate().cancel()
            bar.animate().scaleX(state.baseScaleX).scaleY(state.baseScaleY).translationY(state.baseTranslationY)
                .setDuration(RELEASE_DURATION_MS).setInterpolator(android.view.animation.OvershootInterpolator(RELEASE_OVERSHOOT)).start()
        }

        private fun movedBeyondSlop(caption: Any, state: PressState, event: MotionEvent): Boolean {
            val view = caption as? View ?: return true
            val slop = ViewConfiguration.get(view.context).scaledTouchSlop
            return hypot((event.x - state.downX).toDouble(), (event.y - state.downY).toDouble()) > slop
        }

        private fun state(caption: Any): PressState? = synchronized(states) { states[caption] }
        private class SpringAccess(val getSpring: Method, val isRunning: Method)
        private class PressState {
            var downX = 0f
            var downY = 0f
            var currentDownWasBar = false
            var lastUpWasBar = false
            var lastUpAt = 0L
            var moved = false
            var pressed = false
            var baseScaleX = 1f
            var baseScaleY = 1f
            var baseTranslationY = 0f
            var hasBaseline = false
            var pendingTask: Any? = null
            var longPressRunnable: Runnable? = null
        }

        companion object {
            const val ORDINARY_STATE = 1
            const val BOTTOM_HANDLE_ACTION = 10
            const val HANDLE_ANIMATION_ID = 24
            const val PRESS_SCALE_X = 0.88f
            const val PRESS_SCALE_Y = 0.92f
            const val PRESS_IN_DURATION_MS = 90L
            const val RELEASE_DURATION_MS = 220L
            const val RELEASE_OVERSHOOT = 1.1f
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val CAPTION_CLASS = "com.android.server.wm.FlexibleTaskCaptionView"
        const val BASE_CAPTION_CLASS = "com.android.server.wm.FlexibleCaptionView"
        const val CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val TOUCH_HOOK_ID = "flymefreeform.system.caption_handle_touch"
        const val DOUBLE_TAP_HOOK_ID = "flymefreeform.system.caption_handle_double_tap"
        const val SINGLE_TAP_HOOK_ID = "flymefreeform.system.caption_handle_single_tap"
        const val DOUBLE_TAP_EVENT_HOOK_ID = "flymefreeform.system.caption_handle_double_tap_event"
        const val RELEASE_HOOK_ID = "flymefreeform.system.caption_handle_release"
        const val FLEXIBLE_WINDOW_UTILS_CLASS = "com.android.server.wm.FlexibleWindowUtils"
        const val FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}

private fun Class<*>.requiredCaptionField(name: String): Field {
    var current: Class<*>? = this
    while (current != null) {
        try {
            return current.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    throw NoSuchFieldException(name)
}

private fun Class<*>.requiredCaptionMethod(name: String, parameterCount: Int): Method {
    var current: Class<*>? = this
    while (current != null) {
        val method = current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == parameterCount }
        if (method != null) return method.apply { isAccessible = true }
        current = current.superclass
    }
    throw NoSuchMethodException(name)
}

private fun Method.invokeCaption(receiver: Any?, vararg args: Any?): Any? =
    try {
        invoke(receiver, *args)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
