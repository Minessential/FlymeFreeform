package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.WeakHashMap

/** Adjusts ColorOS freeform caption geometry without shrinking its touch targets. */
@SuppressLint("PrivateApi")
internal class CaptionStyleHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    fun install(classLoader: ClassLoader) {
        try {
            val captionClass = classLoader.loadClass(CAPTION_CLASS)
            val init = captionClass.getDeclaredMethod("initCaptionView")
            val relayout = captionClass.getDeclaredMethod("onRelayoutCaptionView")
            val access = CaptionAccess(captionClass)

            listOf(init, relayout).forEachIndexed { index, method ->
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId(if (index == 0) "flymefreeform.system.caption_style_init" else "flymefreeform.system.caption_style_relayout")
                    .intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? View)?.let { caption ->
                            if (!configuration.snapshot.enabled || !configuration.isAvailable) {
                                return@let
                            }
                            try {
                                access.apply(caption)
                            } catch (exception: ReflectiveOperationException) {
                                module.log(Log.WARN, TAG, "CAPTION_STYLE_REFLECTION_FAILED", exception)
                            } catch (exception: RuntimeException) {
                                module.log(Log.WARN, TAG, "CAPTION_STYLE_APPLY_FAILED", exception)
                            }
                        }
                        result
                    }
            }

            module.log(Log.INFO, TAG, "CAPTION_STYLE_HOOKS_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "CAPTION_STYLE_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "CAPTION_STYLE_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private class CaptionAccess(captionClass: Class<*>) {
        private val contextField = captionClass.requiredCaptionField("mContext")
        private val simpleModeFrame = captionClass.requiredCaptionField("mSimpleModeFrame")
        private val bottomFrame = captionClass.requiredCaptionField("mBottomFrame")
        private val bottomHandle = captionClass.requiredCaptionField("mBottomHandleView")

        // OEM relayout resets dimensions; retain baselines so applying the hook is idempotent.
        private val adjustedHeights = WeakHashMap<View, Int>()
        private val handleBaselines = WeakHashMap<View, HandleBaseline>()

        fun apply(caption: View) {
            val context = contextField.get(caption) as? Context ?: return
            if (context.resources.displayMetrics.density <= 0f) return
            applyBottomStyle(caption)
        }

        fun applyBottomStyle(caption: View) {
            val simpleMode = simpleModeFrame.get(caption) as? View
            simpleMode?.let { frame ->
                if (frame.visibility == View.VISIBLE) resizeHeight(frame, BOTTOM_FRAME_SCALE)
            }
            (bottomFrame.get(caption) as? View)?.let { resizeHeight(it, BOTTOM_FRAME_SCALE) }
            (bottomHandle.get(caption) as? View)?.let { view ->
                styleBottomHandle(view)
            }
        }

        private fun styleBottomHandle(view: View) {
            val params = view.layoutParams ?: return
            val baseline = handleBaselines.getOrPut(view) {
                HandleBaseline(view.scaleY, (params as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0)
            }
            // Visually thicken the bar while keeping its measured/touch bounds unchanged.
            view.scaleY = baseline.scaleY * BOTTOM_HANDLE_THICKNESS
            (params as? FrameLayout.LayoutParams)?.let {
                it.gravity = (it.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) or Gravity.TOP
                it.bottomMargin = baseline.bottomMargin
                view.layoutParams = it
            }
        }

        private fun resizeHeight(view: View, factor: Float) {
            val params = view.layoutParams as? ViewGroup.LayoutParams ?: return
            val previousHeight = adjustedHeights[view]
            val originalHeight = if (previousHeight != null && params.height == previousHeight) {
                (previousHeight / factor).toInt()
            } else {
                params.height
            }
            if (originalHeight <= 0) return
            val height = (originalHeight * factor).toInt().coerceAtLeast(1)
            if (params.height != height) {
                params.height = height
                view.layoutParams = params
            }
            adjustedHeights[view] = height
        }


        private data class HandleBaseline(val scaleY: Float, val bottomMargin: Int)
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val CAPTION_CLASS = "com.android.server.wm.FlexibleTaskCaptionView"
        const val BOTTOM_FRAME_SCALE = 1.5f
        const val BOTTOM_HANDLE_THICKNESS = 1.5f
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
