# 小窗底部横条交互（按压反馈、单击返回、双击关闭）

FlymeFreeform 是面向 ColorOS 的非官方独立实现，与魅族、Flyme、OPPO 或 ColorOS 无关联。

本文是 ColorOS 系统小窗底部区域的开发文档，覆盖普通小窗底部短横条的按压反馈、单击返回和双击关闭。当前工作树已有未提交的 `CaptionStyleHookInstaller`（加高底部 Frame、加粗横条）和 `CaptionHandleInteractionHookInstaller`（按压反馈、双击关闭）；本文在这两个实现的基础上增加单击返回，并保留按压反馈与双击关闭。

## 0. 结论摘要

1. 单击返回必须挂在 `FlexibleTaskCaptionView.onSingleTapConfirmed(MotionEvent)`，不能挂在 `onSingleTapUp`；否则双击的第一下会先返回，无法再“双击关闭”。
2. 返回动作优先调用 `FlexibleTaskController.setFocusedTaskAndReSendBackKey(taskId)`：它先聚焦目标任务，再延迟 40ms 通过 `Instrumentation.sendKeyDownUpSync(KEYCODE_BACK)` 发送系统返回键，保留应用内返回栈。
3. 只受模块总开关 `settings.enabled` 与 `configuration.isAvailable` 控制；不读取 `ModuleEnvironmentState.isModuleAllowed()`，横屏/游戏模式暂停开关对本功能无效。
4. 现有 `CaptionHandleInteractionHookInstaller` 已有 `dispatchTouchEvent`、`onDoubleTap`、`onDoubleTapEvent` 和按压/双击状态机，本次只需新增一个 `onSingleTapConfirmed` Hook、一个可选返回方法引用和 `sendBack()`。
5. 返回与关闭是两件事：单击返回走应用返回键；双击关闭继续走 `exitFlexibleTask(task, true, 0, 0)`。

## 1. 目标与范围

- 普通小窗（`isDefaultZoomState()`，系统状态 `flexibleZoomState == 1`）底部短横条 `mBottomHandleView`：
  - 单指按下时出现按压动画，抬起、取消或开始拖动后回弹。
  - 单击时向小窗所属任务发送一次系统返回键（`KEYCODE_BACK`）：应用内可返回上一页；位于任务根 Activity 时按系统普通返回策略处理。
  - 双击该横条时关闭当前小窗。
- 按压反馈、单击返回和双击关闭都只受模块总开关 `ModulePreferences.KEY_MODULE_ENABLED`（`enabled`）控制；不受“横屏时暂停”或“游戏模式时暂停”开关影响。
- 不改变以下既有行为：
  - 横条上滑切换迷你窗：继续由 `HandleSwipeUpHookInstaller` 的原生模式重映射处理。
  - 横条拖动缩放/移动：继续由系统的 `FlexiblePointerHandler` 处理。
  - 迷你窗双击：系统原生“迷你窗恢复全屏”语义不变。
  - 三键模式：底部 Frame 隐藏，不产生横条动画、返回或关闭。
  - 窗外点击关闭：继续由 `OutsideTapCloseHookInstaller` 处理，二者互不抢占。

### 1.1 术语

- **返回**：不是“关闭小窗”的同义词。返回是把系统 `KEYCODE_BACK` 交给小窗内当前任务，应用可以自行消费（例如返回上一页）；应用没有可返回页面时，才由系统按普通任务返回策略退到后台/上一任务。
- **关闭**：双击横条直接退出当前普通小窗，复用 `FlexibleTaskController.exitFlexibleTask(task, true, 0, 0)`，不经过应用内返回逻辑。
- **按压反馈**：仅视觉动画，不改变窗口状态。


## 2. 已核对的系统组件

### 2.1 采样环境

本轮分析使用当前连接的 ColorOS 17 真机：

| 项目 | 值 |
| --- | --- |
| 机型 | PKX110（CPH2723IN） |
| 系统 | ColorOS V17.0.0，`CPH2723_17.0.0.101(EX01)` |
| Android | 17（API 37） |
| 分辨率 / 密度 | 1216 × 2640，`density 415` |
| 系统服务实现 | `/system/framework/oplus-services.jar`，SHA-256 `f945cbe332ffaafd41908bfa716ae684b7e4064a1be36a8f33972f0c26145275` |
| 资源 | `/system_ext/framework/oplus-framework-res.apk`，SHA-256 `0207c790b37b2cd16ff2c4ffd6eb4fdf5009c19c1c7afb0cb7d604133feedbb7` |

`FlexibleTaskCaptionView` 位于 `oplus-services.jar` 的 `classes3.dex`；布局 `layout/flexible_caption_view`（`0x0c090448`）位于 `oplus-framework-res.apk`。

### 2.2 类关系

```text
android.widget.FrameLayout
  └─ com.android.server.wm.FlexibleCaptionView        // 抽象基类，触摸入口
       └─ com.android.server.wm.FlexibleTaskCaptionView // 小窗标题层实现
```

`FlexibleCaptionView` 实现 `View.OnTouchListener`、`GestureDetector.OnGestureListener`、`GestureDetector.OnDoubleTapListener`；`FlexibleTaskCaptionView` 在 `initCaptionView()` 中 inflate 标题层布局，并创建 `GestureDetector(mContext, this)`。

### 2.3 标题层 View 结构

`initCaptionView()` 使用 `layout/flexible_caption_view`，与底栏相关的层级如下：

```text
FrameLayout (根布局, id=0x0c0200c4)
├─ mThreeButtonFrame (id=0x0c02013f, alpha 初始 0)
│  ├─ mToolbarModeFrame (id=0x0c02014f, 40dp)       // 三键模式
│  └─ mSimpleModeFrame  (id=0x0c0201af, 40dp)       // 手势/简洁模式
│     └─ mControlView (id=0x0c0201cf, 40×10dp)
│        ├─ mLeftEllipse (id=0x0c02020f)
│        ├─ mCenterEllipse (id=0x0c02021f)
│        └─ mRightEllipse (id=0x0c02022f)            // 顶部三个圆点
└─ mBottomFrame (id=0x0c0201df, 16dp, gravity=bottom)
   ├─ mBottomHandleView (id=0x0c0201ef, ImageView)
   │    src=flexible_bottom_fixed, 120×4dp, marginTop=6dp
   └─ mBottomHandleLongView (id=0x0c0201ff, ImageView)
        src=flexible_bottom_handle_long, 144×4dp, marginTop=6dp
```

真机采样中的底部短横条（1216×2640，窗口 scale 0.829）：

![ColorOS 17 小窗底部短横条](images/caption-bottom-handle.png)

关键事实：

- `mBottomHandleView` 的 drawable `flexible_bottom_fixed` 是 120×4dp、圆角 2dp、颜色 `#808080`、`fillAlpha=0.8` 的 VectorDrawable。真机截图中窗口 scale 为 `0.829` 时，横条约 257px 宽、11px 高（含抗锯齿），对应 120dp × 4dp × 0.829 × 2.59 px/dp。
- `mBottomHandleLongView` 在当前 dex 中除构造函数赋值外未见其他直接读取，当前构建下不作为可见横条使用；不要把它当作主目标。
- `mBottomFrame` 默认 alpha 为 0。简洁模式切换动画把 `mBottomFrame` 的 alpha 设为 1；三键模式切换结束时恢复为 0。`isBottomFrameVisible()` 同时要求 `mThreeButtonFrame` 可见、alpha 非 0，且 `mBottomFrame` 可见、alpha 为 1。
- `mBottomHandleView` 的 alpha 由标题层整体显示/聚焦动画控制；按压反馈不要直接改 alpha，否则会与 `updateCaptionAlpha()`、`startCaptionViewAlphaAnimation()` 冲突。

### 2.4 触摸与手势链路

`FlexibleCaptionView.dispatchTouchEvent(MotionEvent)` 是标题层唯一入口：

```text
ACTION_DOWN
  ├─ mHasFirstDownEvent = true
  ├─ calculateActionFlag(ev)
  │    └─ mFlexibleCaptionViewRegionMgr.getFlexibleTaskActionFlag(x, y, this)
  └─ mGestureDetector.onTouchEvent(ev)
       └─ GestureDetector 回调 onSingleTapUp / onDoubleTap / onDoubleTapEvent
  └─ handleEventProcess(ev, mActionFlag, pointerId)
       └─ mFlexiblePointerHandler.onInputEvent(...) 或 handleScaleHandleEvent(...)

ACTION_MOVE
  └─ mGestureDetector.onTouchEvent(ev)
  └─ handleEventProcess(...) -> FlexiblePointerHandler 拖动/缩放

ACTION_UP
  ├─ mGestureDetector.onTouchEvent(ev) -> onSingleTapUp（单击）或 onDoubleTapEvent（双击第二下）
  └─ handleEventProcess(...) -> 结束缩放
```

底部横条的区域判定由 `FlexibleCaptionViewTouchRegionMgr` 完成：

- `mBottomHandleButtonRegion = Rect(bottomHandleView.left, bottomFrame.top, bottomHandleView.right, bottomFrame.bottom)`，即约 120dp 宽 × `mBottomFrame` 高度（默认 16dp，样式 Hook 后 24dp）的触摸区。
- `mBottomHandleExpandRegion` 在按钮区基础上向左/右扩 40dp、向上扩 60dp、向下扩 40dp，用于 `BOTTOM_HANDLE_MOVING_EVENT`（actionFlag 11）；双击判定不能使用这个扩展区，否则横条上方的点击也会被误判。
- `getFlexibleTaskActionFlag(x, y)` 在未锁定的普通状态下返回：
  - `10`：`mBottomHandleButtonRegion` 或底部边缘区，且 `isBottomFrameVisible()` 为真；
  - `8`：底部边缘区，但底栏不可见且不是 mini；
  - `0`：其他区域、应用锁状态等。
- 因此“按在可见短横条上”的可靠条件是 `getFlexibleTaskActionFlag(x, y) == 10`，不能只用坐标或 `mActionFlag` 缓存值。

### 2.5 原生手势语义

`FlexibleTaskCaptionView` 重写了以下手势相关方法：

| 方法 | 原生行为 |
| --- | --- |
| `onSingleTapUp(MotionEvent)` | `shouldHandleSingleTapUp()` 为真时调用 `onSingleTapUp(e, mActionFlag)`；横条 actionFlag 10 落入 switch 的 default 分支，只执行 `notifyFlexibleTaskDragState`，没有关闭/全屏/迷你窗策略，因此不做业务动作。 |
| `onSingleTapConfirmed(MotionEvent)` | 只对 mini/ready 状态做状态切换；普通小窗横条 actionFlag 10 时返回 false。模块在其返回 false 后追加“单击返回”，见 4.3。 |
| `onDoubleTap(MotionEvent)` | 仅 `isMiniZoomState()` 为真时把迷你窗恢复为全屏；普通小窗返回 false。 |
| `onDoubleTapEvent(MotionEvent)` | 固定返回 false。 |

真机点击横条后的系统日志可以确认这条路径：

```text
I/FlexibleTaskCaptionView: onSingleTapUp, need stopPhysicsAnimation
D/FlexibleTaskCaptionView: onSingleTapConfirmed mTask = Task{...}, isMiniZoomState = false, mActionFlag = 10, isReadyZoomState = false
```

这证明：**普通小窗的横条单击和双击在系统里都没有业务动作**，可以在模块侧分别补充“单击返回”和“双击关闭”，不需要改写系统原有按钮/迷你窗语义。

### 2.6 返回动作链路

ColorOS 的 `FlexibleTaskController` 已提供把返回键重新发送给指定小窗任务的公开方法：

```java
public void setFocusedTaskAndReSendBackKey(int taskId)
```

其内部实现（反编译核对）：

```text
setFocusedTaskAndReSendBackKey(taskId):
    mAtms.setFocusedTask(taskId)
    mFlexibleTaskHandler.postDelayed({
        if (mInst == null) mInst = new Instrumentation()
        mInst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }, 40)
```

也就是说，它先把目标任务设为焦点任务，再通过 `Instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)` 向当前焦点窗口发送一次真实的返回键。相比直接调用 `ActivityClientController.onBackPressed(token, callback)`（其语义偏向 token 对应的 Activity 结束/任务回退），这条路径更接近用户按系统返回键：应用自己的 `OnBackPressedDispatcher`、Fragment 返回栈和普通 Activity 返回逻辑都有机会处理。

本功能应优先复用该方法；方法缺失时才考虑“`mAtms.setFocusedTask(taskId)` + 自行 `Instrumentation.sendKeyDownUpSync(KEYCODE_BACK)`”的降级实现。降级实现不要在 UI 线程阻塞执行，需在带 Looper 的后台 Handler 上发送。

真机核对（Settings 普通小窗）：进入“显示与亮度”后任务 activity 栈 `sz=2`，执行 `adb shell input keyevent 4` 后 `sz=1` 且 `FlexibleTaskController.mFlexibleTasks` 仍存在，说明返回只回到应用上一页、不关闭小窗；在 Settings 根页面再次执行返回后 `mFlexibleTasks` 才消失。这个行为正是单击返回和双击关闭必须分开的原因。

### 2.7 关闭 API

`FlexibleTaskController.exitFlexibleTask(Task task, boolean exitFreeFormStack, int flexibleCustomTransition, int sourceFrom)` 是 7 参数内部方法的包装：

```text
exitFlexibleTask(task, exitFreeFormStack, customTransition, sourceFrom)
  -> exitFlexibleTask(task, exitFreeFormStack, customTransition, sourceFrom,
                      withTransition=true, forceResetToFull=false, hideTask=false)
```

该方法自行获取 `WindowManagerGlobalLock`，可由标题层 UI 线程直接调用。现有 `OutsideTapCloseHookInstaller` 已在窗外点击关闭中使用：

```kotlin
exitFlexibleTask.invokeUnwrapped(controller, task, true, 0, 0)
```

本功能应复用同一入口，保持关闭动画、返回栈和任务清理行为一致。

## 3. 模块现状与接入点

### 3.1 Hook 安装链

`ModuleMain.onSystemServerStarting()` -> `SystemServerHookInstaller.install(classLoader)`：

```kotlin
OutsideTapCloseHookInstaller(module, configuration, environment).install(classLoader)
HandleSwipeUpHookInstaller(module, configuration, environment).install(classLoader)
CaptionStyleHookInstaller(module, configuration).install(classLoader)
```

新增交互 Hook 挂在这一链上。横条样式和交互只依赖 `ProcessConfiguration` 中的模块总开关，不使用 `ModuleEnvironmentState.isModuleAllowed()`，因此不受横屏/游戏模式暂停规则控制。

### 3.2 配置与总开关

- `ProcessConfiguration.snapshot` 是只读快照；`ModuleSettingsSnapshot.enabled` 对应总开关，默认 false。
- `configuration.isAvailable` 只有配置成功读取后才为 true；读取失败时按关闭处理。
- `ModuleEnvironmentState.isModuleAllowed()` 包含：监控已启动、显示可用、配置可用、总开关开启、横屏/游戏模式未暂停；横条样式与交互不调用它。
- 总开关通过远程偏好推送和 `ProcessConfiguration` 监听即时生效，不需要重启 system_server；交互 Hook 必须在每次触摸事件读取快照，不能缓存 `enabled`。
- 环境暂停的既有语义见 [横屏与游戏模式暂停](environment-pause.md)：任意暂停规则命中时，手柄上滑迷你窗、窗外点击等增强都会暂停。底部横条样式和交互按本功能约定只随模块总开关启停，不跟随这两项暂停规则。
- 因此交互安装器不需要接收 `ModuleEnvironmentState`；不要在事件路径调用 `isModuleAllowed()`、`isGestureAllowed()` 或 `requiresGestureConfirmation()`，否则会重新引入横屏/游戏模式暂停，违背本需求。

### 3.3 `CaptionStyleHookInstaller` 的影响

工作树中的 `CaptionStyleHookInstaller` 已做以下调整：

- `BOTTOM_FRAME_SCALE = 1.5f`：`mBottomFrame` 高度 16dp -> 24dp。
- `BOTTOM_HANDLE_THICKNESS = 1.5f`：`mBottomHandleView.scaleY` 从 1.0 -> 1.5。

因此按压动画必须遵守两条规则：

1. 不要假设 `mBottomHandleView.scaleY == 1.0`；按下时保存当前 `scaleX/scaleY` 作为基线，抬起时恢复该基线。
2. 不要修改 `mBottomFrame` 的 layoutParams 高度或横条的 `scaleY` 基线；交给样式 Hook 管理。

## 4. 交互设计

### 4.1 统一判定条件

所有新行为使用同一个 `isHandleInteractionAllowed(caption, x, y)` 判定：

```text
settings.enabled == true && configuration.isAvailable == true   // 只受总开关控制
caption.isAttachedToWindow() == true
caption.isDefaultZoomState() == true                  // ordinary zoom, state 1
caption.isBottomFrameVisible() == true
caption.bottomHandleView.visibility == View.VISIBLE
caption.bottomHandleView.alpha > 0f                   // 避免入场/隐藏动画期间误判
caption.getFlexibleTaskActionFlag((int)x, (int)y) == 10 // bottom handle button region
event.pointerCount == 1
event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
```

任一条件不满足：不启动按压动画、不进入单击返回/双击候选、不关闭窗口。

### 4.2 按压反馈

推荐直接缩放可见短横条，不引入额外 View：

| 参数 | 建议值 | 说明 |
| --- | --- | --- |
| `PRESS_SCALE_X` | `0.88f` | 横条水平收紧，短横条宽 120dp，视觉变化约 14dp |
| `PRESS_SCALE_Y` | `0.92f` | 相对当前 `scaleY` 缩放，保留样式 Hook 的 1.5 倍加粗 |
| `PRESS_IN_DURATION_MS` | `90` | 按下要快，避免拖泥带水 |
| `RELEASE_DURATION_MS` | `220` | 抬起回弹略慢 |
| `RELEASE_OVERSHOOT` | `1.1f` | 轻微过冲，接近 ColorOS 的弹性质感 |

实现约定：

- 按下时记录 `baseScaleX = bar.scaleX`、`baseScaleY = bar.scaleY`、`baseTranslationY = bar.translationY`；按下目标为 `baseScaleX * 0.88`、`baseScaleY * 0.92`、`baseTranslationY + 0.5dp`。
- 抬起、取消、第二根手指按下、移动超过 `ViewConfiguration.getScaledTouchSlop()` 时，动画回到记录的基线。
- 不修改 `alpha`；`mBottomHandleView` 的 alpha 被标题层显示/聚焦动画复用。
- 长按超过 `ViewConfiguration.getLongPressTimeout()` 仍未抬起时，主动结束按压态，避免手指停在横条上导致横条一直缩小。
- 以下情况跳过按压动画：
  - `mAnimationHelper.getBottomHandleSpringAnim()?.isRunning() == true`（系统正在播放横条入场缩放，SCALE_X 从 0.05 到 1）。
  - `FlexibleWindowUtils.isFlexibleTaskToggleHandleInfoAnimating(task, 24)` 为 true（系统标题层切换动画）。
  - 任务正在退出/转场。
- 动画使用 `ViewPropertyAnimator`（`bar.animate()`）或 `ObjectAnimator`，全部在 system_server 的 UI 线程执行；一次按压只有两个短动画，无持续帧回调。

备选方案：在 `mBottomFrame` 内懒加载一个不可点击、不可聚焦的 `View` 作为按压高光层，仅动画它的 alpha/scaleX。该方案与系统的 `SCALE_X` 入场弹簧、`scaleY` 样式加粗完全隔离，但会多一个 View 和一次绘制。只有在直接缩放方案在真机上出现系统动画冲突时才采用。

### 4.3 单击返回

**为什么必须等到 `onSingleTapConfirmed`**

单击和双击共用同一根横条。如果直接在 `onSingleTapUp` 里发送返回，用户双击时第一下就已经触发返回，第二下再关闭窗口，行为不可逆。因此必须等系统 `GestureDetector` 确认“不是双击”后再执行返回。`onSingleTapConfirmed` 正是 GestureDetector 在双击超时（`ViewConfiguration.getDoubleTapTimeout()`）之后才回调的单击确认入口；第二下在超时内按下时，GestureDetector 会取消该回调并转入 `onDoubleTap`。

实现方式：

- Hook `FlexibleTaskCaptionView.onSingleTapConfirmed(MotionEvent)`：
  - `GestureDetector` 传入的是该次单击的 `mCurrentDownEvent`（ACTION_DOWN）；其 `getX()/getY()` 即按下点，可直接用于横条命中判定。
  - 先执行原方法；原方法返回 true（mini/ready 状态切换）时直接返回 true，不追加返回动作。
  - 原方法返回 false 后，检查 `event` 对应的按下点是否在横条按钮区、本次手势的 `lastUpWasBar` 是否为 true，并且 `settings.enabled && configuration.isAvailable`。
  - 满足则通过 `caption.post { sendBackIfStillValid(caption, task) }` 发送返回；返回 true 表示该单击已被模块消费。
- 发送前重新校验：
  - `settings.enabled && configuration.isAvailable`（只受总开关控制，不读取横屏/游戏模式暂停状态）。
  - `caption.isDefaultZoomState()`、`caption.isBottomFrameVisible()`、`caption.getFlexibleTaskActionFlag(downX, downY) == 10`。
  - `caption.mTask` 仍是同一对象，且 `controller.getTopZoomTask() === task`。
  - 任务不在退出动画、缩放、拖动或不可分配 Layer 的状态。
- 发送方式：
  - 首选 `controller.setFocusedTaskAndReSendBackKey(task.mTaskId)`。
  - 该方法内部 `mAtms.setFocusedTask(taskId)` 后延迟 40ms 调用 `Instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)`；返回键会进入小窗应用的正常输入/返回链路。
  - 如果目标版本没有该方法，可降级为“`mAtms.setFocusedTask(taskId)` + 后台 Handler 上 `Instrumentation.sendKeyDownUpSync(KEYCODE_BACK)`”，但必须记录 `CAPTION_HANDLE_BACK_FALLBACK`，并在真机确认。

时序与体验：

- 按下/抬起按压动画立即执行；返回动作在双击超时后发生，通常为 `getDoubleTapTimeout()`（系统默认约 300ms）+ 40ms，属于区分单双击的必要延迟。
- 如果用户在超时内第二下按在横条上，GestureDetector 取消 `onSingleTapConfirmed`，不会发送返回；第二下抬起后走双击关闭。
- 如果第二下按在标题层其他区域，GestureDetector 仍可能把它识别为双击并取消 `onSingleTapConfirmed`，但模块的 `onDoubleTap` 因两次落点不都在横条而拒绝关闭；此时本次单击返回会被系统 GestureDetector 吞掉，属于可接受的边界。不要为了补偿这个边界而在别处补发返回，否则会误伤标题层的正常双击语义。

### 4.4 双击关闭

建议复用系统 `GestureDetector` 已经完成的双击识别，不额外实现时间/距离判定：

- Hook `FlexibleTaskCaptionView.onDoubleTap(MotionEvent)`：
  - 先执行原方法。
  - 原方法返回 true（迷你窗恢复全屏）时直接返回 true，不改变原生语义。
  - 原方法返回 false 时，检查 `onDoubleTap` 的 `e` 是否位于横条按钮区，且本次手势的第一次按下也位于横条按钮区；满足则记录 `PendingClose(task, eventTime, lastX, lastY)`，返回 false，让 `GestureDetector` 继续把后续事件送给 `onDoubleTapEvent`。
- Hook `FlexibleTaskCaptionView.onDoubleTapEvent(MotionEvent)`：
  - `PendingClose` 存在且 `actionMasked == ACTION_UP`：清除 pending，`caption.post { closeIfStillValid(task) }`，返回 true。
  - `ACTION_CANCEL`：清除 pending，返回 false。
  - 其他 action：返回 false，不消费。

“两次按下都在横条按钮区”的判定通过 `dispatchTouchEvent` 中记录的每次 ACTION_DOWN/ACTION_UP 状态完成；不能只用 `onDoubleTap` 第二下的坐标，否则第一下在别处、第二下在横条也可能触发关闭。

`closeIfStillValid` 在 post 后重新校验：

```text
configuration.snapshot.enabled && configuration.isAvailable
controller = caption.mFlexibleTaskController
task == caption.mTask
controller.getTopZoomTask() === task
caption.isDefaultZoomState()
caption.isBottomFrameVisible()
caption.getFlexibleTaskActionFlag(lastX, lastY) == 10
task 不在退出动画/不可分配 Layer 状态
-> controller.exitFlexibleTask(task, true, 0, 0)
```

其中 `getTopZoomTask() === task` 保证只关闭标题层所属的顶层小窗；多小窗重叠、任务已被替换或窗口正在关闭时直接放弃，避免关错任务。

在第二下 `ACTION_UP` 关闭而不是第二下 `ACTION_DOWN` 关闭的原因：

- 便于在第二下按下时继续显示按压动画，抬起后动画和关闭衔接自然。
- 普通小窗的原生 `onDoubleTap` 本来没有业务动作，不存在必须抢在 DOWN 处理的系统状态。
- 原生迷你窗 `onDoubleTap` 返回 true 的分支不受影响。

### 4.5 与原生手势的优先级

| 输入 | 系统处理 | 模块处理 |
| --- | --- | --- |
| 单击横条（应用内可返回） | `onSingleTapUp` actionFlag 10 无业务动作；`onSingleTapConfirmed` 返回 false | 按下/抬起动画；双击超时后发送 `KEYCODE_BACK`，应用返回上一页，窗口保留 |
| 单击横条（应用位于任务根） | 同上 | 按下/抬起动画；系统按普通返回策略退到后台/上一任务，窗口可能随之退出 |
| 双击横条（普通小窗） | `onDoubleTap` 返回 false；`onDoubleTapEvent` 返回 false | 两轮按压动画，第二下抬起后关闭；不发送返回键 |
| 拖动横条 | `handleScaleEventProcess` 进入缩放流程 | 移动超过 touch slop 后取消按压动画，不返回、不关闭 |
| 从横条快速上滑 | `FlexibleTaskScaleManager.getGestureMode` -> 迷你窗 | 取消按压动画，不返回、不关闭 |
| 按住横条不放 | GestureDetector 取消单击/双击候选 | 长按超时后结束按压态，不返回、不关闭 |
| 双击迷你窗 | `onDoubleTap` 返回 true，恢复全屏 | 不返回、不关闭；横条本来不可见 |
| 第二根手指按下 | 系统多指缩放 | 立即取消按压动画和单击/双击候选 |
| 三键模式 | `mBottomFrame` alpha 0 | 无动画、无返回、无关闭 |
| 应用锁状态 | `getFlexibleTaskActionFlag` 返回 0 | 无动画、无返回、无关闭 |
| 横屏/游戏模式暂停 | 系统窗口正常 | 横条样式、按压反馈、单击返回和双击关闭不受影响 |
| 总开关关闭 | 系统窗口正常 | 横条样式、按压反馈、单击返回和双击关闭均停用 |

## 5. 实现方案

### 5.1 文件改动

现有工作树已经新增：

```text
app/src/main/kotlin/io/github/mangi/flymefreeform/hook/CaptionHandleInteractionHookInstaller.kt
```

本次单点击返回应直接修改该文件，不新建第二套 installers：

- 在 `CaptionAccess` 中增加可选的 `setFocusedTaskAndReSendBackKey(int)` 方法引用、`Task.mTaskId` 字段读取和 `sendBack()`。
- 在 `install()` 中增加 `FlexibleTaskCaptionView.onSingleTapConfirmed(MotionEvent)` Hook；`SystemServerHookInstaller` 已安装该 installer，不需要再改安装链。

可选抽取（推荐但非必需）：

```text
app/src/main/kotlin/io/github/mangi/flymefreeform/hook/CaptionViewAccess.kt
```

把 `CaptionStyleHookInstaller` 与 `CaptionHandleInteractionHookInstaller` 各自维护的 `requiredCaptionField()` / `requiredCaptionMethod()` 反射工具和字段读取集中到一个内部访问类，避免两处字段名漂移。该重构不影响本文的 Hook 语义，可以单独提交。

需要修改：

```text
app/src/main/kotlin/io/github/mangi/flymefreeform/hook/CaptionHandleInteractionHookInstaller.kt
```

不需要修改：

- `SystemServerHookInstaller.kt`（`CaptionHandleInteractionHookInstaller` 已安装）
- `ModulePreferences.kt`
- `ModuleSettingsSnapshot.kt`
- `FrameworkConnectionRepository.kt`
- `ControlScreen.kt`
- `strings.xml`

本期不新增独立开关，按压反馈、单击返回和双击关闭全部跟随总开关。若后续需要独立开关，再新增类似 `caption_handle_double_tap_close_enabled` 的偏好，并在 `WindowInteractionCard` 中加 `SwitchPreference`；在此之前不要预埋无人控制的配置键。

现有实现核对（基于工作树中的 `CaptionHandleInteractionHookInstaller.kt`）：

- `install()` 已注册 `dispatchTouchEvent`、`onDoubleTap`、`onDoubleTapEvent` 和可选的 `onCaptionViewRelease`；单击返回新增第 5 个 Hook `onSingleTapConfirmed`。
- `CaptionAccess.onTouch()` 已维护 `downX/downY/currentDownWasBar/lastUpWasBar/lastUpAt/moved`，可直接用于单击确认，不需要新增手势引擎。
- `CaptionAccess.armClose()/submitClose()` 已实现普通小窗双击关闭；单击返回应走独立 `sendBack()`，不要复用 `exitFlexibleTask`。
- 当前代码只根据 `configuration.snapshot.enabled` 和 `configuration.isAvailable` 判断总开关，没有调用 `ModuleEnvironmentState`，与本文要求一致。

### 5.2 Hook 点

| Hook | 类 / 方法 | 用途 |
| --- | --- | --- |
| 1 | `FlexibleCaptionView.dispatchTouchEvent(MotionEvent)` | 记录横条按下/抬起状态；启动、取消、回弹按压动画；记录单击是否落在横条 |
| 2 | `FlexibleTaskCaptionView.onSingleTapConfirmed(MotionEvent)` | 原方法返回 false 后，在双击超时位置执行单击返回 |
| 3 | `FlexibleTaskCaptionView.onDoubleTap(MotionEvent)` | 原方法返回 false 后判断横条双击，建立 pending close |
| 4 | `FlexibleTaskCaptionView.onDoubleTapEvent(MotionEvent)` | 第二下 ACTION_UP 时提交关闭；ACTION_CANCEL 清理 |
| 5（可选） | `FlexibleTaskCaptionView.onCaptionViewRelease()` | 取消动画、清理 per-caption 状态 |

`dispatchTouchEvent` 在 `FlexibleCaptionView` 中声明，不在子类中；通过 `captionClass.getMethod("dispatchTouchEvent", MotionEvent::class.java)` 或加载父类后 `getDeclaredMethod(...)` 定位。其余方法都在 `FlexibleTaskCaptionView` 中声明。每个 Hook 都必须先确认 `chain.thisObject` 是 `FlexibleTaskCaptionView` 实例，避免父类被其他子类复用时误触发。

Hook ID 建议：

```text
flymefreeform.system.caption_handle_touch
flymefreeform.system.caption_handle_single_tap
flymefreeform.system.caption_handle_double_tap
flymefreeform.system.caption_handle_double_tap_event
flymefreeform.system.caption_handle_release
```

统一使用 `XposedInterface.ExceptionMode.PROTECTIVE`；新增 Hook 的异常不能影响系统原有标题层处理。

### 5.3 伪代码（在现有 installer 上增量）

现有 `CaptionHandleInteractionHookInstaller` 已包含 `dispatchTouchEvent`、`onDoubleTap`、`onDoubleTapEvent` 和 `onCaptionViewRelease` 四个 Hook，`PressState` 已有 `downX/downY/currentDownWasBar/lastUpWasBar/lastUpAt/pendingTask`；单击返回只增加以下内容：

```kotlin
// install(): 在 onDoubleTapEvent Hook 之后追加
val singleTapHook = captionClass.getDeclaredMethod("onSingleTapConfirmed", MotionEvent::class.java)
hook(singleTapHook, SINGLE_TAP_HOOK_ID) { chain ->
    val original = chain.proceed() as? Boolean ?: false
    if (original) return@hook true
    val caption = chain.thisObject
    val event = chain.getArg(0) as? MotionEvent
    if (caption != null && event != null && access.sendBack(caption, event, configuration)) {
        return@hook true
    }
    original
}
```

`CaptionAccess` 增加：

```kotlin
private val taskIdField = classLoader.loadClass(TASK_CLASS).requiredCaptionField("mTaskId")
private val setFocusedTaskAndReSendBackKey: Method? = try {
    controllerClass.requiredCaptionMethod("setFocusedTaskAndReSendBackKey", 1)
} catch (_: NoSuchMethodException) {
    null
}

fun sendBack(caption: Any, event: MotionEvent, configuration: ProcessConfiguration): Boolean {
    val state = state(caption) ?: return false
    if (!state.lastUpWasBar || !isAllowed(caption, event.x, event.y, event, configuration)) return false
    val method = setFocusedTaskAndReSendBackKey ?: return false
    val task = taskField.get(caption) ?: return false
    val controller = controllerField.get(caption) ?: return false
    (caption as? View)?.post {
        try {
            if (!configuration.isAvailable || !configuration.snapshot.enabled) return@post
            if (taskField.get(caption) !== task) return@post
            if (getTopZoomTask.invokeCaption(controller) !== task) return@post
            if (isTaskInFlexibleState.invokeCaption(controller, task, ORDINARY_STATE) != true) return@post
            if (!isHandleVisible(caption) || actionFlag(caption, event.x, event.y) != BOTTOM_HANDLE_ACTION) return@post
            method.invokeCaption(controller, taskIdField.getInt(task))
        } catch (_: ReflectiveOperationException) {
            // 任务/标题层正在消失时 fail-open
        } catch (_: RuntimeException) {
            // 同上
        }
    }
    return true
}
```

要点：

- `onSingleTapConfirmed` 的首个事件是第一次 ACTION_DOWN；此时 `state.lastUpWasBar` 已经在第一次 ACTION_UP 时设为 true。不要新增 `onSingleTapUp` Hook。
- `sendBack` 在 `caption.post` 后再次检查总开关、任务身份、`getTopZoomTask()`、`isTaskInFlexibleState(..., 1)`、底栏可见性和 actionFlag 10；任何一项变化都放弃返回。
- `setFocusedTaskAndReSendBackKey` 为 null 时只禁用单击返回，按压反馈和双击关闭仍保留；安装期记录 `CAPTION_HANDLE_BACK_UNAVAILABLE`。
- `PressState` 不需要新字段；现有 `downX/downY` 可用于日志和校验。

### 5.4 关键实现细节

**反射字段**

现有 `CaptionAccess` 已经维护 `controllerField`、`taskField`、`bottomFrameField`、`handleField`，以及 `isDefaultZoomState`、`isBottomFrameVisible`、`getFlexibleTaskActionFlag`、`isTaskInFlexibleState`、`getTopZoomTask`、`exitFlexibleTask`；单击返回只需补充：

- `mTask.mTaskId` 是 `Task` 上的 int 字段，可通过 `Task.class.getDeclaredField("mTaskId")` 读取；`setFocusedTaskAndReSendBackKey(int)` 是 `FlexibleTaskController` 的公开方法，需要作为可选方法探测。
- `setFocusedTaskAndReSendBackKey` 只服务于单击返回，必须单独探测：缺失时记录 `CAPTION_HANDLE_BACK_UNAVAILABLE`，保留按压反馈和双击关闭，不要让整个交互 Installer 因一个方法缺失而放弃全部 Hook。
- `mBottomHandleLongView` 只作兜底，不参与按压动画；如果未来布局调整导致 `mBottomHandleView` 不可见，先记录并确认可见性，不要直接切到 LongView。

**动画基线**

`CaptionStyleHookInstaller` 可能在 `onRelayoutCaptionView()` 后重新设置 `mBottomHandleView.scaleY = baselineY * 1.5`。按压动画在每次 ACTION_DOWN 时读取并保存当前 `scaleX/scaleY`，因此：

- 正常情况基线是 `scaleX=1.0, scaleY=1.5`；
- 如果动画过程中发生 relayout，最坏情况是回弹到旧基线，不应把 1.0 写死；
- 不要在 `onRelayoutCaptionView` 中启动按压动画，避免和样式 Hook 的 layoutParams 修改互相覆盖。

**线程与生命周期**

- 所有触摸回调、View 动画、`caption.post` 都在 system_server 主线程；不得从 Binder 线程直接操作 View。
- `exitFlexibleTask` 内部自行获取 WMS global lock；但仍在 `caption.post` 中执行，避免在 `GestureDetector` 回调里同步做窗口事务。
- `setFocusedTaskAndReSendBackKey` 会自行把 `Instrumentation.sendKeyDownUpSync` 投递到 `mFlexibleTaskHandler`，不要在 UI 线程自行执行阻塞式按键注入。
- per-caption 状态使用 `WeakHashMap<Any, PressState>`，不持有强引用；在 `onCaptionViewRelease` / `onDetachedFromWindow` 时取消动画并移除状态。
- 标题层可能因旋转、分屏、任务切换重新创建；新 View 从空闲状态开始，不复用旧任务的 pending double tap。

**日志与节流**

建议日志代码：

```text
CAPTION_HANDLE_HOOKS_INSTALLED
CAPTION_HANDLE_TARGET_UNAVAILABLE
CAPTION_HANDLE_TOUCH_FAILED
CAPTION_HANDLE_BACK_FAILED
CAPTION_HANDLE_BACK_SKIPPED
CAPTION_HANDLE_BACK_UNAVAILABLE
CAPTION_HANDLE_BACK_FALLBACK
CAPTION_HANDLE_DOUBLE_TAP_FAILED
CAPTION_HANDLE_CLOSE_FAILED
CAPTION_HANDLE_CLOSE_SKIPPED
```

沿用现有 Hook 的 `FAILURE_LOG_INTERVAL_MS = 10_000L` 节流；正常点击不要打 INFO 日志，避免 system_server 日志噪声。

## 6. 开关与配置契约

| 开关/状态 | 来源 | 本功能行为 |
| --- | --- | --- |
| 模块总开关 | `ModuleSettingsSnapshot.enabled` / `KEY_MODULE_ENABLED` | 关闭时完全无动画、无单击返回、无双击关闭 |
| 配置可用性 | `ProcessConfiguration.isAvailable` | 读取失败时按关闭处理 |
| 环境暂停 | 不适用 | 横条样式、按压反馈、单击返回和双击关闭不受横屏/游戏模式暂停开关影响 |
| 应用锁 | `FlexibleWindowUtils.isTopInAppLockState(task)`（由 `getFlexibleTaskActionFlag` 间接判断） | 不响应 |
| 窗口状态 | `isDefaultZoomState()` + `isBottomFrameVisible()` | 非普通小窗/横条不可见时不响应 |
| 手势状态 | 单指、手指、TOUCHSCREEN | 多指或非触摸不响应 |

总开关关闭后：

- 已存在的按压动画应在下一次触摸/取消时回弹；
- 已经进入 `PendingClose` 的双击候选在 `closeIfStillValid` 再次检查时被丢弃；
- `onSingleTapConfirmed` 到达时再次检查总开关，关闭后不再发送返回；
- 不修改系统标题层的原有触摸区域，系统小窗仍可正常使用。

## 7. 兼容性与边界条件

### 7.1 系统版本

- 本文基于 ColorOS 17 / Android 17 的 `oplus-services.jar` 与 `oplus-framework-res.apk` 核对；ColorOS 16 或其他 Android 版本的类、字段和布局 ID 可能不同。
- Hook 安装失败（类/方法/字段缺失）时必须 fail-open：记录 `CAPTION_HANDLE_TARGET_UNAVAILABLE`，不安装任何新行为，系统小窗保持原样。
- 不要按 `versionName` 精确放行；沿用项目现有做法，以类、方法、字段存在性和运行状态为准。

### 7.2 窗口状态

- 仅处理 `flexibleZoomState == 1` 的普通小窗；`mini`（2）、`ready`、`tiling` 等状态不处理。
- 三键模式 `mBottomFrame` alpha 0，不产生横条交互。
- 应用锁状态 `isTopInAppLockState()` 为 true 时 `getFlexibleTaskActionFlag` 返回 0，直接跳过。
- “返回”交给小窗内当前任务：应用有上一页时只返回上一页，窗口保留；应用位于任务根或没有可返回页面时，系统按普通返回策略退到后台/上一任务，窗口可能随之退出。不要在模块侧把单击返回实现成 `exitFlexibleTask`，否则会跳过应用内返回栈。
- 桌面/外部显示器：`OplusConnectedDisplayManager.isDesktopDisplayConnectedForTask(task)` 可能改变原生单击/双击语义；首版建议在外部显示器连接时跳过双击关闭和单击返回，真机确认后再放开。
- 多小窗：返回和关闭前都必须验证 `controller.getTopZoomTask() === task`，不能依赖标题层实例或坐标猜测。
- 输入法：`mBottomFrame` 位于任务底部，输入法弹出时标题层可能被平移/裁短；按压区、单击返回和动画坐标都使用当前 View 坐标，不缓存屏幕坐标。
- 旋转/ relayout：每次 ACTION_DOWN 重新读取 `getFlexibleTaskActionFlag`；动画基线重新采样。

### 7.3 手势冲突

- 单击返回必须挂在 `onSingleTapConfirmed`，不能挂在 `onSingleTapUp`；后者会在第一下抬起时立即触发，导致双击关闭前先执行一次返回。`onSingleTapConfirmed` 由系统 GestureDetector 在双击超时后才回调，天然与双击互斥。
- 如果第二下按在横条外但仍落在 GestureDetector 的双击范围内，GestureDetector 会取消第一下的 `onSingleTapConfirmed`，而模块的横条双击判定又因落点不在横条而拒绝关闭；这一次单击返回会被吞掉。不要在 `onDoubleTap` 里补发返回，避免误伤标题层正常双击。
- `onDoubleTap` 必须先去执行原方法；迷你窗原生双击恢复全屏不能被子类 Hook 覆盖。
- `onDoubleTapEvent` 只在 `PendingClose` 存在时消费 ACTION_UP；其他事件继续交还原方法。
- `handleRawDoubleTapFallback()` 在 `FlexibleWindowUtils.isSupportFlexibleExchangeContinuousAnimation()` 为 true 时可能直接调用 `onDoubleTap`；Hook 的 pending 状态必须幂等，不能因为同一第二下被 raw fallback 和 GestureDetector 各调用一次而关闭两次。`PendingClose` 在提交后立即清空。
- `mBottomHandleExpandRegion` 是拖动/上滑的扩展区，不能用于单击返回或双击命中；否则横条上方 60dp 内的点击也会误触发。
- 按压动画在移动超过 touch slop 时必须取消；否则用户拖动横条缩放时，横条会保持缩小状态。
- 系统横条入场弹簧（`mAnimationHelper.getBottomHandleSpringAnim()`）运行时不要启动按压动画，避免两个动画同时写 `SCALE_X`。
- `setFocusedTaskAndReSendBackKey` 内部固定延迟 40ms 再注入返回键；如果任务在这 40ms 内被其他操作关闭或替换，返回键可能落到新的焦点窗口。调用前必须做顶层任务校验，并在真机确认多窗口/快速切后台场景。

### 7.4 与 `CaptionStyleHookInstaller` 的协作

- 样式 Hook 负责 `mBottomFrame` 高度和 `mBottomHandleView.scaleY` 基线；交互 Hook 只读取基线，不写 layoutParams。
- 如果后续把两者合并为同一个 Installer，需保证 `initCaptionView` / `onRelayoutCaptionView` 的后置处理顺序：先应用样式，再安装/刷新交互视图或基线。
- 测试时同时验证“未应用样式”和“应用样式（1.5 倍加粗）”两种构建，避免按压动画把横条恢复成未加粗的 4dp。

## 8. 验证计划

### 8.1 单元测试

建议把与 Android 无关的判定抽成 `CaptionHandleInteractionPolicy` 或纯 Kotlin engine，覆盖：

- 总开关关闭或配置不可用 -> 不按压、不返回、不关闭。
- 普通小窗 + 横条可见 + actionFlag 10 -> 允许。
- mini/ready/tiling、三键模式、应用锁 -> 拒绝。
- 单击横条、第一下落下后没有第二下 -> 产生一次 `SingleTapBack`。
- 单击后第二下快速落在横条 -> 取消 `SingleTapBack`，转为双击关闭候选。
- 第一下在横条、第二下在横条外 -> 不关闭；是否保留单击返回由 GestureDetector 行为决定（当前实现按第 7.3 节接受被吞掉的边界）。
- 第一下在横条外、第二下在横条内 -> 不关闭。
- 两下之间超时、移动超 touch slop、多指、长按 -> 不返回、不关闭。
- 第二下 ACTION_UP 前 ACTION_CANCEL -> 清除候选。
- 同一 caption 连续两次双击 -> 只提交一次关闭；连续单击 -> 每次只提交一次返回。

### 8.2 真机验证步骤

1. 构建并安装模块 APK，重启设备或 system_server，确认模块在 LSPosed 中启用且作用域包含 system_server。
2. 打开模块总开关，打开任意应用的普通小窗。可用系统命令快速创建测试小窗：

```bash
adb shell am force-stop com.android.settings
adb shell am start -n com.android.settings/.Settings --windowingMode 100
adb shell dumpsys activity activities | grep -A8 'FlexibleTaskController:'
```

3. 截屏确认底部横条位置：

```bash
adb exec-out screencap -p > /tmp/flexible-window.png
```

4. 先在小窗内进入一个非根页面（例如 Settings 的“显示与亮度”），再单击横条中心：应看到横条短暂收紧后回弹，约双击超时后应用返回上一页，小窗保留。
5. 在应用位于任务根时单击横条：应按系统普通返回策略退到后台/上一任务；这是返回键语义，不是模块直接关闭小窗。
6. 双击横条中心：应看到两次按压反馈后窗口关闭，任务从 `dumpsys activity activities` 消失；双击不应先触发返回。
7. 按住横条向上快速滑动：应切换迷你窗，不能触发返回或关闭。
8. 按住横条拖动：应缩放/移动窗口，横条不能保持缩小状态。
9. 迷你窗双击标题区：应恢复全屏（原生行为），不能返回或关闭迷你窗。
10. 切换到三键模式：底部横条不可见，点击原横条位置无新行为。
11. 关闭模块总开关后重复单击/双击：无动画、无返回、无关闭。
12. 打开横屏/游戏模式暂停开关后重复测试：横条样式、按压反馈、单击返回和双击关闭仍正常；关闭模块总开关后这些行为停用。
13. 旋转屏幕、弹出输入法、打开两个小窗、切换前后台后重复第 4–8 步。

### 8.3 日志核对

过滤 system_server 日志：

```bash
adb logcat -v brief | grep -E 'FlymeFreeform|FlexibleTaskCaptionView|FlexibleCaptionView'
```

预期：

- 正常启用时不出现 `CAPTION_HANDLE_*` 失败码；
- 单击返回后不出现 `CAPTION_HANDLE_BACK_FAILED` / `CAPTION_HANDLE_BACK_SKIPPED`；使用降级路径时出现 `CAPTION_HANDLE_BACK_FALLBACK`；
- 关闭总开关后不出现 `CAPTION_HANDLE_CLOSE_FAILED`；
- 目标设备类/字段存在时出现 `CAPTION_HANDLE_HOOKS_INSTALLED`；
- 系统版本变化导致 Hook 失败时只有一次 `CAPTION_HANDLE_TARGET_UNAVAILABLE`，系统小窗不受影响。

### 8.4 验收标准

- [ ] 普通小窗横条单击有按压动画，并在双击超时后执行一次系统返回；应用内上一页可正常返回，窗口保留。
- [ ] 应用位于任务根时单击横条按系统普通返回策略处理，不出现模块直接调用 `exitFlexibleTask` 的跳过行为。
- [ ] 普通小窗横条双击关闭当前小窗，且只关闭一次；双击不会先触发返回。
- [ ] 单击/双击都会在总开关关闭时失效。
- [ ] 横屏/游戏模式暂停开关不影响横条样式、按压反馈、单击返回和双击关闭。
- [ ] 通过横条上滑切换迷你窗、拖动缩放、双击迷你窗恢复全屏均保持原生行为。
- [ ] 三键模式、应用锁、mini/ready/tiling 状态无新增行为。
- [ ] 多小窗场景只对标题层所属的顶层普通小窗执行返回或关闭。
- [ ] 旋转、输入法、多个小窗、前后台切换后无残留按压状态、错发返回或错关任务。

## 9. 风险与回滚

| 风险 | 处理 |
| --- | --- |
| 系统标题层入场动画与按压动画同时写 `SCALE_X` | 检测 `getBottomHandleSpringAnim().isRunning()` / `isFlexibleTaskToggleHandleInfoAnimating(task, 24)` 时跳过按压 |
| 样式 Hook 的 `scaleY` 被按压动画覆盖 | 每次按下保存当前基线，抬起恢复；不在交互 Hook 中写死 1.0 |
| 单击返回必须先于双击判定导致误返回 | 只挂 `onSingleTapConfirmed`，等待 GestureDetector 双击超时；不在 `onSingleTapUp` 发送返回 |
| `setFocusedTaskAndReSendBackKey` 在目标版本缺失 | 安装期方法探测失败则记录 `CAPTION_HANDLE_BACK_UNAVAILABLE`，跳过单击返回但保留按压/双击；可选降级为 `mAtms.setFocusedTask` + 后台 `Instrumentation`，需真机验证 |
| 返回键在任务切换的 40ms 窗口内落到错误窗口 | 调用前校验 `getTopZoomTask() === task`；快速切后台/多窗口场景列入手工回归 |
| `onDoubleTap` 原生 mini 行为被破坏 | 先执行原方法，原方法返回 true 时直接透传 |
| raw double tap fallback 与 GestureDetector 重复回调 | pending 状态幂等，提交后立即清空，Close 前再次校验 top task |
| 系统升级导致字段/方法缺失 | 安装期捕获 `ReflectiveOperationException` / `LinkageError`，fail-open，不修改系统行为 |
| 关闭错任务 | `getTopZoomTask() === task` + `isDefaultZoomState()` + `isBottomFrameVisible()` 重新校验 |
| 按压动画在窗口关闭后继续运行 | `onCaptionViewRelease` / `onDetachedFromWindow` 取消动画并清理 WeakHashMap |

回滚方式：移除 `SystemServerHookInstaller` 中的 `CaptionHandleInteractionHookInstaller` 安装行并重新构建/重启；本功能没有持久化配置键，不需要数据迁移。

## 10. 附录：核对记录

- `FlexibleTaskCaptionView` 类位于 `oplus-services.jar!/classes3.dex`；`oplus-services.jar` SHA-256 见 2.1。
- 布局 `flexible_caption_view.xml` 的关键资源：
  - `mBottomFrame`：`0x0c0201df` / `flexible_bottom`
  - `mBottomHandleView`：`0x0c0201ef` / `bottom_handle_flexible`
  - `mBottomHandleLongView`：`0x0c0201ff` / `bottom_handle_long_flexible`
- 真机采样（1216×2640，窗口 frame `[104,282][1112,2074]`，task scale `0.829`）：
  - 横条可见像素约 `x=479..735, y=2034..2044`，约 257×11 px；
  - 与 120dp × 4dp、`density 415`、scale 0.829 的计算一致。
- 横条单击日志确认 `mActionFlag = 10`、`isReadyZoomState = false`，系统不执行状态切换；模块新增的单击返回由 `onSingleTapConfirmed` 追加。
- 返回动作核对：`FlexibleTaskController.setFocusedTaskAndReSendBackKey(int)` 调用 `mAtms.setFocusedTask(taskId)` 后，在 `mFlexibleTaskHandler` 延迟 40ms 执行 `Instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)`。
- 关闭 API 与窗外点击共用 `FlexibleTaskController.exitFlexibleTask(task, true, 0, 0)`。

以上静态分析与真机采样用于确定 Hook 点；按压动画、单击返回、双击关闭和新版本兼容性仍需按第 8 节在真实设备上逐项验收。
