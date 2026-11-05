# Smart Sidebar「开关保持开启、界面与手势不生效」实现报告

> 分析对象：本仓库 `FlymeFreeform` + 设备上的 `com.coloros.smartsidebar` 17.9.8（versionCode `170009008`）
>
> 设备环境：CPH2723IN / PKX110 / ColorOS `V17.0.0` / Android 17（API 37）
>
> 分析方式：只读 `adb shell` 采集 + MT MCP 打开 `Smart Sidebar_17.9.8.apk` 反编译核对。**本次没有对设备执行任何写操作。**

---

## 0. 结论（先看这里）

这个需求**不能只靠改设置完成**，但可以做到，且不需要关掉 Smart Sidebar 的组件。

核心结论：

1. **`edge_panel_toggle=1` 是必须保留的。** 本模块有四处硬性检查它（详见 2.2），关了它模块的「更多」面板、侧边栏工具/快捷入口、分身应用都会失效。
2. **`edge_panel_os_float_bar_alpha=0` 和 `edge_panel_float_bar_permanent=0` 不能解决问题。**
   - 前者只改变浮标的绘制透明度，不会清除 `sidebar_scene_region`。
   - 后者的“自动隐藏”只把 `FloatBarMainView` 设为 `INVISIBLE`，没有调用 `resetInputArea`，`sidebar_scene_region` 仍然保留。
3. 设备现在真正被占用的不是浮标窗口本身（`OplusOSEdgeFloatBar` 是 `NOT_TOUCHABLE` 的 20×155 px 窗口），而是 **`sidebar_scene_region = 1143 446 1217 603`** 这个 74×157 px 的右侧边缘区域：SystemUI 的 `SideGestureDetector` 会把这整段区域从返回手势中排除，交给 Smart Sidebar 识别。
4. **推荐方案：保留 `edge_panel_toggle=1`、`EdgePanelService`、`UIService`、`SideBarProvider` 全部启用；只给 `com.coloros.smartsidebar:edgepanel` 增加 Xposed Hook，把浮标“永不显示”，并把 `InputUtils` 写入的 `sidebar_scene_region` 强制为 `0 0 0 0`。**
5. 这个方案不会影响模块依赖 Smart Sidebar 的部分，因为：
   - `PanelMainView` 构造时的初始状态就是 `FLOAT_BAR_SHOWING`，`sidebarHidden()` 只检查状态枚举，不检查浮标是否可见；
   - 模块打开「更多」时走 `SideBarProvider.bindServiceForTransferDock → ResidentDataManager.i() → j(NONE)`，会主动 `startService + bindService` 拉起 `UIService`，并不依赖浮标窗口；
   - `UIService` 照常创建 `PanelMainView`、`EntryBeanHelper`，模块的 `ColorOsAllAppsContent` / `ColorOsSidebarEntries` 继续工作。

一句话概括：**把 Smart Sidebar 变成一个“没有任何界面、没有任何边缘手势，但 Binder / Provider / Entry 能力全部保留”的后台组件。**

---

## 1. 本模块对 Smart Sidebar 的依赖到底在哪

### 1.1 依赖的功能

| 功能 | 入口 | 依赖的侧边栏能力 |
|---|---|---|
| 扇形菜单里的「更多」 | `ColorOsFreeformCoordinator.onMorePanelRequested()` → `ColorOsSidebarClient.open()` | 绑定 `UIService`，在侧边栏进程里创建 `AllAppPanelView`，加载全部应用并点击启动 |
| 侧边栏工具 / 快捷入口 | `PinnedTarget.Sidebar` + `ColorOsSidebarEntryConnection` → `ColorOsSidebarEntryEndpoint` | `EntryBeanHelper` 的 `getAllAppListForTool` / `getShownShortcuts` |
| 分身应用 | `ColorOsSidebarEntries.load(includeClones = true)` | `EntryBeanHelper.getShownApps()` + `LauncherApps` 获取分身用户 |
| 分身应用快捷方式 | `ColorOsSidebarEntries.load()` 内 `AppShortcutSource.forClone` | 侧边栏条目里的 `getMutiUserId` / `getActivity` |

不依赖侧边栏的：普通应用列表、普通小窗启动、角落手势本身、窗外点击关闭、上滑迷你窗。

### 1.2 关键代码路径

- `app/src/main/kotlin/io/github/mangi/flymefreeform/platform/coloros/ColorOsSidebarClient.kt:91-123`
  - 负责「更多」面板的 prepare/bind。
  - `prepareAndBind()` 先通过 `ContentResolver.call("bindServiceForTransferDock")` 让常驻端按 `NONE` 任务拉起 `UIService`，然后自己再 `bindService(BIND_ACTION)`。
  - 代码注释 `ColorOsSidebarClient.kt:111` 明确写了：“让常驻端按 NONE 任务绑定 UI 并注册数据回调；单独创建 UIService 不会完成这次握手。”
- `app/src/main/kotlin/io/github/mangi/flymefreeform/platform/coloros/ColorOsAllAppsEndpoint.kt:197-259`
  - `prepare()` 创建 `ColorOsAllAppsContent`，`advance()` 等它 `ready()`。
  - `environmentAllowed()` 要求 `edge_panel_toggle == 1`。
- `app/src/main/kotlin/io/github/mangi/flymefreeform/platform/coloros/ColorOsAllAppsContent.kt:80-83`
  - `ready()` 要求 `getMPanelMainView() != null` 且 resident binder 存活。
  - `sidebarHidden()` 要求 `PanelMainView.getMState().name == "FLOAT_BAR_SHOWING"`。
- `app/src/main/kotlin/io/github/mangi/flymefreeform/platform/coloros/ColorOsSidebarEntryConnection.kt:56-71`
  - 目录/启动短连接同样先 `bindServiceForTransferDock` 再绑定 `UIService`。
- `app/src/main/kotlin/io/github/mangi/flymefreeform/platform/coloros/ColorOsSidebarEntryEndpoint.kt:190-194`
  - `allowed()` 要求 `edge_panel_toggle == 1`。
- `app/src/main/kotlin/io/github/mangi/flymefreeform/platform/coloros/ColorOsSidebarEntries.kt:54-57`
  - `ready()` 要求 `EntryBeanHelper.getActiveInstance() != null`、`getMPanelMainView() != null`、`getMResidentProcessHandler()` 的 Binder 存活。

### 1.3 四处硬性 `edge_panel_toggle` 检查

| 文件 | 行 | 条件 | 影响 |
|---|---:|---|---|
| `ColorOsSidebarClient.kt` | 104 | `Settings.Secure.getInt(..., "edge_panel_toggle", -1) != 1` → `cleaned` | 「更多」面板不会打开 |
| `ColorOsAllAppsEndpoint.kt` | 259 | 同上 | 「更多」面板运行中也会被取消 |
| `ColorOsSidebarEntryConnection.kt` | 58 | 同上 | 侧边栏目录/启动请求直接结束 |
| `ColorOsSidebarEntryEndpoint.kt` | 194 | 同上 | 侧边栏条目列表/启动被拒绝 |

因此 **`edge_panel_toggle` 必须保持 `1`**，且不能通过关闭侧边栏开关来“自然隐藏”。

---

## 2. Smart Sidebar APK 的结构

### 2.1 两个关键进程

APK 的 `<application android:process=":edgepanel">` 决定默认进程；`UIService` 显式声明 `android:process=":ui"`。

| 进程 | 关键类/组件 | 作用 |
|---|---|---|
| `com.coloros.smartsidebar:edgepanel` | `com.coloros.edgepanel.service.EdgePanelService`（`oppo.intent.action.EDGE_PANEL_SERVICE`） | 常驻端；`onCreate()` 初始化 `EdgePanelController` / `DisplayDataManager`，创建浮标 |
| 同上 | `com.coloros.edgepanel.api.SideBarProvider`（authority `com.coloros.sidebar`） | `bindServiceForTransferDock`、`hideFloatBarViewForEdit`、`getWindowFloatBar` 等 |
| 同上 | `com.oplus.smartsidebar.permanent.floatbar.FloatBarMainView` / `FloatBarView` | 右侧浮标窗口 `OplusOSEdgeFloatBar` |
| 同上 | `com.oplus.smartsidebar.utils.InputUtils` | 写 `sidebar_scene_region`，更新 `OplusGestureMonitorManager` 的 `"sidebar"` 区域 |
| `com.coloros.smartsidebar:ui` | `com.oplus.smartsidebar.panelview.edgepanel.UIService`（`oplus.intent.action.EDGE_PANEL_UI_SERVICE`） | 侧栏面板 UI；模块当前 Hook 的进程 |
| 同上 | `PanelMainView` / `EntryBeanHelper` / `AllAppPanelView` | 面板状态机、条目数据、全部应用列表 |
| `com.android.systemui` | `com.oplus.systemui.edgepanel.EdgePanelUI` / `EdgePanelHelper` | 读取 `edge_panel_toggle`，`switchOn()` 时绑定 `EdgePanelService` |
| 同上 | `com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector` | 读取 `sidebar_scene_region`，把该区域从返回手势中排除 |

### 2.2 `edge_panel_toggle=1` 时发生了什么

```
SystemUI EdgePanelUI
  └─ EdgePanelToggleObserver 读到 edge_panel_toggle == 1
       └─ EdgePanelHelper.switchOn()
            └─ bindService(oppo.intent.action.EDGE_PANEL_SERVICE)
                 └─ :edgepanel EdgePanelService.onCreate()
                      └─ EdgePanelController.getInstance()
                           └─ DisplayDataManager.n()
                                └─ DisplayDataManager.z(...)
                                     ├─ new FloatBarMainView(context)
                                     ├─ addFloatBarMainViewIfNeeded()
                                     │    └─ WindowUtil.addView(... "OplusOSEdgeFloatBar")
                                     └─ updateFloatBarVisibility(true, ...)
                                          ├─ FloatBarView.appear(...)
                                          └─ resetInputArea(true, ...)
                                               └─ InputUtils.s(x1, y1, x2, y2)
                                                    └─ InputUtils.t(rect, false)
                                                         ├─ Settings.Secure.putString("sidebar_scene_region", ...)
                                                         └─ OplusGestureMonitorManager
                                                              .updateRegionInputEvent("sidebar", ...)
```

同时 `InputEventSubject` 在 `:edgepanel` 启动时调用 `InputUtils.m()` 注册 `"sidebar"` 区域输入回调；`handleInputEvent()` 用 `InputUtils.e()`（即静态 `Rect b`）判断按下点是否在侧栏区域。

### 2.3 设备实测：浮标窗口本身不接收触摸

当前设备 `adb shell dumpsys window windows`：

```
Window #17 Window{f3ac538 u0 OplusOSEdgeFloatBar}:
  mOwnerUid=10234 ... package=com.coloros.smartsidebar appop=NONE
  mAttrs={(1196,447)(20x155) ... ty=2314 ...
    fl=NOT_FOCUSABLE NOT_TOUCHABLE NOT_TOUCH_MODAL ...}
  Requested w=20 h=155
  frame=[1196,447][1216,602]
  isVisible=true
```

另外：

```
Window #16 Window{f70c3ad u0 ColorOSEdgePanel}:
  mSession=Session{... 12974:u0a10234}   # :ui 进程
  mAttrs={(0,0)(1x1) ... ty=2314
    fl=NOT_FOCUSABLE NOT_TOUCHABLE ...}
```

结论：

- `OplusOSEdgeFloatBar` 是 `:edgepanel` 画的 20×155 px 视觉窗口，**本身是 `NOT_TOUCHABLE`**，不是手指被拦截的原因。
- `ColorOSEdgePanel` 只是 `:ui` 的 1×1 占位窗口，也不是占用区域。
- 真正的“右侧区域”来自 `sidebar_scene_region`：设备当前值为 `1143 446 1217 603`（74×157 px，右边缘）。SystemUI 的 `SideGestureDetector.updateSideBarSceneRegion()` 会读取它，`onMotionEventImpl()` 中 `mSideBarSceneRegion` 非空时把这段区域从返回手势排除；同时 `:edgepanel` 的 `InputEventSubject` 用 `InputUtils.e()` 识别该区域内的按下事件。

### 2.4 为什么调透明度 / 自动隐藏没有用

`FloatBarView.updateFloatBarAlpha(boolean permanent, float alpha)` 只设置 `mCurrentAlpha / mStrokeAlpha / mSaveAlpha` 并 `invalidate()`，**没有调用 `InputUtils`**，所以 `sidebar_scene_region` 不变。

`FloatBarMainView.scheduleAutoHideDelay()` 只执行：

```java
FloatBarView.disappear$default(getMFloatBar(), false, false, new scheduleAutoHideDelay.1.1(this), ...)
// callback:
this.this$0.setVisibility(4);
```

它没有调用 `FloatBarMainView.updateFloatBarVisibility(false)`，因此没有走 `resetInputArea(false)`，**`sidebar_scene_region` 仍然保留**。

`edge_panel_os_float_bar_alpha=0` 只是把 `mPaint` 的 alpha 设为 0；窗口仍然 `isVisible=true`，区域仍然有效。当前设备正好是：

```
edge_panel_toggle=1
edge_panel_float_bar_permanent=1
edge_panel_os_float_bar_alpha=0
sidebar_scene_region=1143 446 1217 603
```

这组值说明“视觉上可能已经透明，但右侧手势区域仍然被占用”。

---

## 3. 推荐实现方案：保留开关，抑制 `:edgepanel` 的 UI 与区域

### 3.1 总体设计

```
[模块设置 hideSystemSidebarUi = true]
        │
        ▼
[system_server ColorOsFreeformCoordinator]
   ├─ （可选）把 edge_panel_toggle 写为 1
   └─ （可选）启动时把 sidebar_scene_region 清为 "0 0 0 0"
        │
        ▼
[SmartSidebar :edgepanel]
   EdgePanelService 正常运行（常驻端/Provider/EntryBean 都保留）
   ├─ Hook FloatBarMainView.updateFloatBarVisibility(..., true, ...)
   │     → 把 show 强制改为 false（changeVisibleQuickly 也必须为 false）：浮标永不出现，并清零输入区域
   ├─ Hook InputUtils.s(int,int,int,int)
   │     → 强制 0,0,0,0（覆盖 updateFloatBarSize / updateRotation 等直接 resetInputArea 的路径）
   ├─ Hook InputUtils.m() / InputUtils.r(Rect)
   │     → 不注册 / 强制零区域（防止初始 1×1 区域）
   └─ 可选：Hook InputEventSubject.handleInputEvent(MotionEvent)
         → 完全屏蔽原生侧栏的手势输入解析
        │
        ▼
[模块自己的角落手势] → ColorOsSidebarClient.open()
   → ContentResolver.call(SideBarProvider, "bindServiceForTransferDock")
   → ResidentDataManager.i() → j(NONE)
   → startService + bindService UIService
   → UIService 创建 PanelMainView（初始状态 FLOAT_BAR_SHOWING）
   → 模块绑定 UIService，创建自己的 AllAppPanelView 窗口
   → 「更多」/ 侧栏条目/分身应用全部照常工作
```

### 3.2 为什么 `PanelMainView` 状态不会因为隐藏浮标而坏掉

反编译 `PanelMainView.<init>`：

```java
State r5 = State.FLOAT_BAR_SHOWING;
this.mStartState = r5;
this.mState = r5;
this.mLastState = r5;
```

也就是说：

- `PanelMainView` 的初始状态就是 `FLOAT_BAR_SHOWING`；
- 模块的 `sidebarHidden()`（`ColorOsAllAppsContent.kt:83`、`ColorOsSidebarEntries.kt:57`）只看这个枚举值；
- 模块的「更多」窗口是 `ColorOsAllAppsWindow` 自己 `WindowManager.addView(TYPE_APPLICATION_OVERLAY, title="FlymeFreeformAllApps")`，不调用原生 `PanelMainView.show()`，也不依赖浮标；
- 因此把 `FloatBarMainView` 隐藏、把 `sidebar_scene_region` 清零，不会让 `sidebarHidden()` 变成 false。

### 3.3 为什么模块的「更多」还能拉起 UIService

`ColorOsSidebarClient.prepareAndBind()` 的调用链：

```
ContentResolver.call(
  "content://com.coloros.sidebar",
  "bindServiceForTransferDock",
  ...)
  ↓
SideBarProvider.call("bindServiceForTransferDock")
  ↓
ResidentDataManager.a.i()
  ↓
ResidentDataManager.j(StartUITaskType.NONE)
  ↓
Intent(UIService) + startServiceAsUser + bindServiceAsUser
  ↓
UIService.onCreate()
  ↓
ViewDataHandlerImpl.onUIServiceCreate()
  ↓
new PanelMainView(App.sContext)
```

所以：**即使浮标窗口完全不存在，`UIService` 仍会被模块主动拉起；`PanelMainView` 仍然会被创建；`getMResidentProcessHandler` 仍会在常驻端绑定 `UIService` 时注册。** 这是推荐方案不破坏模块依赖的关键依据。

### 3.4 需要改的模块代码

#### A. 新增开关（建议命名）

| 文件 | 改动 |
|---|---|
| `config/ModulePreferences.kt` | `const val KEY_HIDE_SYSTEM_SIDEBAR_UI = "hide_system_sidebar_ui"`；`DEFAULT_HIDE_SYSTEM_SIDEBAR_UI` |
| `config/ModuleSettingsSnapshot.kt` | 增加 `val hideSystemSidebarUi: Boolean = ...`；`writeTo` / `readFrom` 读写 |
| `framework/FrameworkConnectionRepository.kt` | 增加 `fun setHideSystemSidebarUi(enabled: Boolean) = updateSettings { it.copy(hideSystemSidebarUi = enabled) }` |
| `MainActivity.kt` / `ui/FlymeFreeformNavHost.kt` / `ui/ControlScreen.kt` | 把回调接到设置页 `SwitchPreference`，建议放在 `SettingsCard` 或 `RuntimeCard` |
| `res/values/strings.xml` | 新增标题/说明，例如“隐藏系统侧边栏界面”“保留智能侧边栏依赖能力，但隐藏浮标并禁用边缘手势” |

> 建议默认值：如果是这个定制分支，可默认 `true`；如果要保持原模块行为，默认 `false`，由用户显式开启。

#### B. 扩展进程范围

`app/src/main/resources/META-INF/xposed/scope.list` 已经是包级：

```text
com.coloros.smartsidebar
```

不需要新增包；需要让模块代码接受 `:edgepanel` 进程。

`ModuleMain.kt`：

```kotlin
// ColorOsSidebarTarget.kt
const val EDGE_PROCESS_NAME = "$PACKAGE_NAME:edgepanel"
```

```kotlin
// ModuleMain.kt: isExpectedProcess
param.processName == ColorOsSidebarTarget.EDGE_PROCESS_NAME
```

```kotlin
// ModuleMain.kt: onPackageReady
processName == ColorOsSidebarTarget.EDGE_PROCESS_NAME &&
    param.packageName == ColorOsSidebarTarget.PACKAGE_NAME -> {
        hooksInstalled = true
        SidebarHostHookInstaller(this, configuration).install(param.classLoader)
    }
```

> `SidebarHookInstaller` 继续只在 `:ui` 安装；新增 `SidebarHostHookInstaller` 只处理 `:edgepanel` 的浮标/输入区域，两者互不替代。

#### C. 新增 `SidebarHostHookInstaller`

建议伪代码（真实实现要沿用现有 `SidebarHookInstaller` 的异常处理和日志风格）：

```kotlin
internal class SidebarHostHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    fun install(loader: ClassLoader) {
        try {
            val floatBarClass = loader.loadClass(
                "com.oplus.smartsidebar.permanent.floatbar.FloatBarMainView"
            )
            val inputUtilsClass = loader.loadClass(
                "com.oplus.smartsidebar.utils.InputUtils"
            )

            val updateVisibility = floatBarClass.getDeclaredMethod(
                "updateFloatBarVisibility",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            module.hook(updateVisibility)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.sidebar_host.float_bar_visibility")
                .intercept { chain ->
                    if (!suppressionEnabled()) return@intercept chain.proceed()
                    // 注意第三个参数必须为 false：true 会跳过 resetInputArea，
                    // 导致 sidebar_scene_region 不会被清零。
                    chain.proceed(arrayOf(false, false, false, 0))
                }

            val setInputArea = inputUtilsClass.getDeclaredMethod(
                "s",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            module.hook(setInputArea)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.sidebar_host.input_area")
                .intercept { chain ->
                    if (!suppressionEnabled()) return@intercept chain.proceed()
                    chain.proceed(arrayOf(0, 0, 0, 0))
                }

            val registerInput = inputUtilsClass.getDeclaredMethod("m")
            module.hook(registerInput)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.sidebar_host.input_register")
                .intercept { chain ->
                    if (suppressionEnabled()) null else chain.proceed()
                }

            // 可选防御：如果未来版本存在直接调用 r(Rect) 的路径，也强制零区域。
            val updateInput = inputUtilsClass.getDeclaredMethod("r", Rect::class.java)
            module.hook(updateInput)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.sidebar_host.input_update")
                .intercept { chain ->
                    if (!suppressionEnabled()) return@intercept chain.proceed()
                    chain.proceed(arrayOf(Rect(0, 0, 0, 0)))
                }

            // 可选：清掉已注册的 "sidebar" 区域，并把当前浮标立即隐藏。
            Handler(Looper.getMainLooper()).post { cleanupCurrentState(loader) }
            module.log(Log.INFO, TAG, "SIDEBAR_HOST_HOOK_INSTALLED", null)
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SIDEBAR_HOST_SIGNATURE_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SIDEBAR_HOST_HOOK_FAILED", exception)
        } catch (error: LinkageError) {
            module.log(Log.WARN, TAG, "SIDEBAR_HOST_LINKAGE_FAILED", error)
        }
    }

    private fun suppressionEnabled(): Boolean =
        configuration.snapshot.enabled && configuration.snapshot.hideSystemSidebarUi
}
```

`configuration.observe { ... }` 还可以处理“功能关闭”的恢复：当 `hideSystemSidebarUi` 从 `true` 变为 `false` 时，在主线程对当前 `DisplayDataManager.a.k()` 调用 `updateFloatBarVisibility(true, false, false, 0)`，并把 `InputUtils` 的 Hook 切回 pass-through。这样关闭功能后不必强制重启 `:edgepanel`。

`cleanupCurrentState()` 建议做三件事（全部用反射，失败只记日志）：

1. `InputUtils.q()`：注销可能已经注册的 `OplusGestureMonitorManager` 区域；
2. `InputUtils.s(0, 0, 0, 0)`：把静态 `Rect b` 和 `sidebar_scene_region` 归零；
3. `DisplayDataManager.a.k()` 拿到 `FloatBarMainView`，反射调用 `updateFloatBarVisibility(false, false, false, 0)`；如果为空则跳过。第三个参数必须是 `false`，否则会跳过 `resetInputArea`，区域不会清零。

> 注意：`InputUtils.s` 会在 `InputUtils.t` 内写 `Settings.Secure` 和 `OplusGestureMonitorManager`；`s(0,0,0,0)` 是安全的清零入口。不要只写 `Settings.Secure`，因为 `:edgepanel` 里的静态 `Rect b` 和 `OplusGestureMonitorManager` 的注册区域是独立状态。
>
> 如果某些版本没有可用的 `s` 入口，可退化为 Hook `InputUtils.t(Rect, boolean)`，把参数替换为 `Rect(0,0,0,0)` 和 `true`（`true` 保证走写入分支，而 `false` 在“矩形未变化”时会提前返回）。
>
> 如果设备没有 `OplusGestureMonitorManager`（`InputUtils.g()` 为 false），`InputEventSubject` 会走 `IOplusExService.registerInputEvent` 路径；此时再加一层 Hook：让 `InputEventSubject.handleInputEvent(MotionEvent)` 直接 no-op，或让 `InputUtils.e()` 永远返回 `Rect(0,0,0,0)`。

#### D. `:ui` 侧的兜底（可选，第二阶段再做）

正常用户只会从屏幕边缘触发侧栏；`sidebar_scene_region` 清零后原生面板不会被触发。如果要求“从设置页预览、语音助手等所有入口都不能打开原生面板”，可在 `:ui` 侧再加一层：

- 拦截 `com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.ViewDataHandlerImpl.showOrCloseUserPanel(...)`，当 `show == true` 时直接返回 `false`；
- **不要简单地把 `PanelMainView.show()` no-op**，因为原生流程可能已经把 `mState` 改成 `EDGE_PANEL_SHOWING`，导致模块的 `sidebarHidden()` 变为 false；要拦就拦在状态机之前。

这一层不是第一版必需项；先做 `:edgepanel` 的浮标 + 区域抑制即可。

### 3.5 系统开关的处理（可选但建议做）

需求是“打开系统中 smart side bar 的开关”，可以在 `system_server` 侧做一次性同步：

```kotlin
private fun ensureSidebarSwitchOn(settings: ModuleSettingsSnapshot, userId: Int) {
    if (!settings.enabled || !settings.hideSystemSidebarUi) return
    val resolver = context.contentResolver
    if (Settings.Secure.getIntForUser(resolver, "edge_panel_toggle", -1, userId) != 1) {
        Settings.Secure.putIntForUser(resolver, "edge_panel_toggle", 1, userId)
    }
}
```

放在 `ColorOsFreeformCoordinator.applySettings()` 或新的 `SidebarHostPolicy` 中调用。

- `system_server` 有 `WRITE_SECURE_SETTINGS`，比在模块 App 进程里写更合适；
- 只在 `enabled && hideSystemSidebarUi` 时写，避免模块关闭时干预用户；
- 如果希望“用户手动关掉后自动恢复”，再加 `ContentObserver`；但这是强制性策略，建议单独做成“强制保持侧边栏开关”选项，不要默认静默执行；
- 如用户原来就是关闭状态，可考虑在 `ModulePreferences` 里记录原值，关闭该功能时恢复；否则关闭模块后开关仍为开启，需要明确说明。

同时建议在 `ColorOsFreeformCoordinator.start()` 中做一次 `sidebar_scene_region = "0 0 0 0"` 的启动清理，防止上一次运行留下的旧值在 SystemUI 启动时被读入：

```kotlin
Settings.Secure.putStringForUser(
    context.contentResolver,
    "sidebar_scene_region",
    "0 0 0 0",
    userId,
)
```

这只是一个兜底；真正持续保证零值的是 `:edgepanel` 的 `InputUtils` Hook。

---

## 4. 不推荐的方案与原因

### 4.1 只改设置

| 设置 | 实际效果 | 是否满足 |
|---|---|---|
| `edge_panel_toggle=0` | 模块四处检查失败，侧边栏功能全失效 | ❌ 破坏模块依赖 |
| `edge_panel_os_float_bar_alpha=0` | 浮标绘制透明，`sidebar_scene_region` 不变 | ❌ 区域仍占用 |
| `edge_panel_float_bar_permanent=0` | 3 秒后 `setVisibility(4)`，区域不变 | ❌ 区域仍占用 |
| 写 `sidebar_scene_region=0 0 0 0` 一次 | SystemUI 侧暂时恢复返回手势；但 `:edgepanel` 的 `InputUtils.b` / `OplusGestureMonitorManager` 注册区域可能仍是旧值，下一次浮标更新又会写回 | ⚠️ 只能作为辅助 |
| 禁用 `com.coloros.smartsidebar` 包/组件 | `ColorOsSidebarTarget.supportedUid()` 要求 `app.enabled && service.enabled && service.exported`，模块直接失效 | ❌ |
| 只禁用 `EdgePanelService` | `:edgepanel` 常驻初始化、`ResidentDataManager` 和条目数据链路可能断裂；需要大量验证 | ⚠️ 高风险，不建议 |

### 4.2 只用 SystemUI Hook

只 Hook `SideGestureDetector.updateSideBarSceneRegion()` 让它忽略区域，可以恢复返回手势，但 `:edgepanel` 的 `InputEventSubject` 仍可能通过 `OplusGestureMonitorManager` 收到 `"sidebar"` 区域事件并打开原生面板。反之，只 Hook `:edgepanel` 的 `InputUtils` 而不让 SystemUI 读到零，也会留下返回手势被排除的问题。**两边最终都依赖 `sidebar_scene_region` 这个设置，所以让 `:edgepanel` 把它写成 0 是两个问题的最小交集。**

### 4.3 把模块从 `edge_panel_toggle` 解耦（开关保持关闭）

理论上可以：

- 删除/放宽四处 `edge_panel_toggle == 1` 检查；
- 让 `ColorOsSidebarClient` 仍然调用 `bindServiceForTransferDock`，由 `ResidentDataManager.j(NONE)` 启动 `UIService`；
- Smart Sidebar 总开关保持 `0`，系统不会显示浮标/区域。

优点：完全不需要 Hook，右侧天然无占用。
风险：这是改变模块契约，需要验证 `EdgePanelService` 不在运行时 `ResidentDataManager`/`EntryBeanHelper` 是否仍会完整初始化；如果厂商版本依赖 `EdgePanelService.onCreate()` 初始化 `DisplayDataManager`，条目数据可能不完整。**建议作为实验分支验证，不作为第一方案。**

### 4.4 通过 `com.oplus.smartsidebar_disable` App Feature

SystemUI 的 `EdgePanelUI.start()` 里有一句：

```java
if (AppFeatureProviderUtils.isFeatureSupport(
        contentResolver,
        AppFeatureProviderUtils.FeatureID.STATIC_COMPONENT,
        "com.oplus.smartsidebar_disable")) {
    return;
}
```

如果能把该 feature 置为 true，SystemUI 就不会绑定 `EdgePanelService`，可能从源头避免浮标。但它依赖 `com.oplus.customize.coreapp.configmanager.configprovider.AppFeatureProvider` 的内部配置，不同 ROM 是否可写/可覆盖未知；并且模块仍需要 `UIService`/`ResidentDataManager` 链路，必须实测。**可作为备选调研项，不建议作为主实现。**

---

## 5. 验证方案

### 5.1 当前设备只读基线（本次已采集）

```bash
adb shell getprop ro.build.version.oplusrom          # V17.0.0
adb shell getprop ro.build.version.release           # 17
adb shell getprop ro.build.version.sdk               # 37
adb shell dumpsys package com.coloros.smartsidebar | grep -E 'versionName|versionCode'
# versionName=17.9.8  versionCode=170009008

adb shell settings get secure edge_panel_toggle               # 1
adb shell settings get secure edge_panel_float_bar_permanent  # 1
adb shell settings get secure edge_panel_os_float_bar_alpha   # 0
adb shell settings get secure sidebar_scene_region            # 1143 446 1217 603
```

浮标窗口：

```bash
adb shell dumpsys window windows | awk '/OplusOSEdgeFloatBar/,/Window #18/'
# 20x155 at [1196,447]-[1216,602], ty=2314,
# fl=NOT_FOCUSABLE NOT_TOUCHABLE NOT_TOUCH_MODAL ...
```

### 5.2 实现后的验收命令（只读）

```bash
# 1. 总开关仍为 1
adb shell settings get secure edge_panel_toggle

# 2. 区域被清零
adb shell settings get secure sidebar_scene_region
# 期望：0 0 0 0

# 3. 浮标窗口不存在，或存在但不可见/0 尺寸
adb shell dumpsys window windows | grep -A20 OplusOSEdgeFloatBar
# 期望：无输出，或 isVisible=false / frame 0x0

# 4. 侧边栏进程仍活著
adb shell ps -A | grep com.coloros.smartsidebar
# 期望：:edgepanel 和 :ui 都存在（至少 :edgepanel 存在，:ui 可由模块按需拉起）

# 5. 模块服务仍能拉起 UIService（打开「更多」后）
adb shell dumpsys activity service com.coloros.smartsidebar | grep -E 'UIService|EdgePanelService'
```

### 5.3 功能验收清单

| 场景 | 期望 |
|---|---|
| 右侧边缘滑动（原侧栏区域） | 不出现 Smart Sidebar 面板；返回手势在整条右边缘正常 |
| 右边缘原地点击 | 不出现浮标/面板 |
| 模块左下/右下角斜向内上滑 | 正常出现扇形菜单 |
| 扇形菜单选「更多」 | 正常打开模块自己的全部应用面板；搜索、点击、关闭正常 |
| 固定一个侧边栏工具 | 扇形菜单中显示该工具；点击能启动 |
| 固定一个分身应用 | 显示分身图标/角标；点击能启动到分身 |
| 旋转屏幕 | 上述行为不变，区域仍为 `0 0 0 0` |
| 锁屏/解锁 | 上述行为不变 |
| 重启 | 开机后区域仍为 `0 0 0 0`，浮标不出现 |
| 关闭模块的「隐藏系统侧边栏界面」 | 原生 Smart Sidebar 浮标/手势恢复（可能需要重启 `:edgepanel` 或切换一次总开关） |
| 切换总开关 1→0→1 | 模块功能在 1 时正常；0 时应按原逻辑失效（如果模块依赖开关） |

### 5.4 建议的单元/回归测试

1. `ModuleSettingsSnapshotTest`：新增 `hideSystemSidebarUi` 的默认值、写入/读取、旧配置兼容。
2. `SidebarDexQueryTest` 或新增 `SidebarHostDexQueryTest`：使用 17.9.8 fixture 验证 `FloatBarMainView.updateFloatBarVisibility`、`InputUtils.s/m` 的签名定位。
3. Hook 安装的反射单元测试：类/方法缺失时返回诊断码而不是抛异常。
4. 真机回归：至少覆盖 `sidebar-dex-resolution.md` 中已有的 16.14.0 / 16.14.6 / 17.9.2 / 17.9.8 样本。

---

## 6. 风险与兼容性

| 风险 | 说明 | 缓解 |
|---|---|---|
| 版本混淆/改名 | `FloatBarMainView`、`InputUtils.s` 是当前 17.9.8 的名字，未来版本可能变化 | 沿用现有 `SidebarDexQuery` 的 DexKit 能力定位；不要硬编码混淆后的短名；规则版本单独升级 |
| Hook 安装时机晚于浮标创建 | 开机时 `EdgePanelService.onCreate()` 可能已创建浮标 | `onPackageReady()` 安装后立即 `cleanupCurrentState()`；必要时在 `EdgePanelService.onCreate` 前 Hook |
| `InputUtils.m()` 已注册过旧区域 | Hook 前可能已经 `registerRegionInputEvent` | 安装后调用 `InputUtils.q()` 注销，再让 `m()` 走 no-op |
| 系统设置被用户手动关闭 | 模块功能依赖 `edge_panel_toggle=1` | 可在 system_server 的开关策略中同步；或明确告知用户 |
| 多用户 | `Settings.Secure` 是按用户存储；不同用户进程可能独立 | 用 `createContextAsUser` / `putIntForUser`；每个用户进程都安装 Hook |
| 旋转/折叠/平板 | `FloatBarMainView.updateRotationByObserver()` 会直接 `resetInputArea(true)` | `InputUtils.s` Hook 强制零，不依赖 `updateFloatBarVisibility` 单一路径 |
| `FloatBarMainView` 的窗口仍然存在 | 即使不可见，`dumpsys` 可能仍显示 20×155 透明窗口 | 可选 `addFloatBarMainViewIfNeeded()` no-op 彻底不创建；需回归测试 `getWindowFloatBar` 等外部查询 |
| 与 `:ui` 现有 Hook 冲突 | `SidebarHookInstaller` 已 Hook `PanelMainView.changeState` | 新增 `:edgepanel` Hook 不要碰 `PanelMainView`；`SidebarHostHookInstaller` 只处理浮标/InputUtils |

---

## 7. 最终建议

**推荐落地顺序：**

1. 先实现 `:edgepanel` 的 `FloatBarMainView.updateFloatBarVisibility` + `InputUtils.s` 两个 Hook，验证：
   - `sidebar_scene_region` 变成 `0 0 0 0`；
   - 右侧返回手势恢复；
   - 模块「更多」和侧边栏固定项仍可用。
2. 再加 `InputUtils.m()` no-op / `InputUtils.q()` 清理，解决启动初期 1×1 区域和旧注册残留。
3. 再加模块设置开关、`ModuleMain` 进程范围、system_server 的开关同步。
4. 最后再考虑 `:ui` 的 `showOrCloseUserPanel` 兜底和 `addFloatBarMainViewIfNeeded` 彻底移除窗口。

**不要做的事：**

- 不要关闭 `edge_panel_toggle`；
- 不要 `pm disable` / `pm suspend` `com.coloros.smartsidebar` 或 `UIService`；
- 不要只设 `edge_panel_os_float_bar_alpha=0`；
- 不要只改 `sidebar_scene_region` 设置而不 Hook `:edgepanel` 的 `InputUtils` / `OplusGestureMonitorManager` 路径；
- 不要 Hook `PanelMainView.show()` 而不同时保护 `mState`，否则可能让模块自己的 `sidebarHidden()` 条件失效。

---

## 附录 A：关键类/方法索引

| 类 | 方法/字段 | 作用 |
|---|---|---|
| `com.oplus.smartsidebar.permanent.floatbar.FloatBarMainView` | `updateFloatBarVisibility(boolean, boolean, boolean, int)` | 显示/隐藏浮标；隐藏分支调用 `resetInputArea(false)` |
| 同上 | `resetInputArea(boolean, boolean)` | `InputUtils.s(x1,y1,x2,y2)`；右边缘 72/74 px 区域 |
| 同上 | `addFloatBarMainViewIfNeeded()` | `WindowUtil.addView(... "OplusOSEdgeFloatBar")` |
| 同上 | `scheduleAutoHideDelay()` | 只做 `disappear` + `setVisibility(4)`，不清区域 |
| `com.oplus.smartsidebar.utils.InputUtils` | `s(int,int,int,int)` | `resetInputArea` 的统一入口 |
| 同上 | `t(Rect, boolean)` | 写 `sidebar_scene_region` + `OplusGestureMonitorManager.updateRegionInputEvent` |
| 同上 | `m()` | `registerRegionInputEvent("sidebar", ...)` |
| 同上 | `q()` | `unregisterRegionInputEvent` |
| 同上 | `b` / `e()` | 静态 `Rect`，`InputEventSubject` 用来判断侧栏按下区域 |
| `com.coloros.edgepanel.scene.subjects.InputEventSubject` | `onSubscribe()` / `handleInputEvent(MotionEvent)` | 侧栏手势输入回调 |
| `com.oplus.smartsidebar.permanent.models.DisplayDataManager` | `z(boolean,boolean,boolean,boolean,boolean)` | 创建/销毁 `FloatBarMainView`，调用 `updateFloatBarVisibility` |
| 同上 | `n()` | 读取 `edge_panel_float_bar_permanent` / `edge_panel_system_ui_slide_gesture` |
| `com.oplus.smartsidebar.permanent.models.ResidentDataManager` | `i()` → `j(NONE)` | 启动并绑定 `UIService` |
| `com.coloros.edgepanel.api.SideBarProvider` | `call("bindServiceForTransferDock")` | 常驻端启动/绑定 UI 的入口 |
| `com.oplus.smartsidebar.panelview.edgepanel.UIService` | `onBind()` / `onCreate()` | 返回 `IUiProcessHandler`；创建 `PanelMainView` |
| `com.oplus.smartsidebar.panelview.edgepanel.PanelMainView` | `<init>()` | 初始 `mState = FLOAT_BAR_SHOWING` |
| `com.oplus.systemui.edgepanel.EdgePanelUI` | `updateToggle()` | `edge_panel_toggle` → `EdgePanelHelper.switchOn/off` |
| `com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector` | `updateSideBarSceneRegion()` | 读取 `sidebar_scene_region`，排除返回手势区域 |

## 附录 B：本次设备证据摘要

```text
Product: CPH2723IN / PKX110
ColorOS: V17.0.0
Android: 17 / API 37

com.coloros.smartsidebar versionName=17.9.8 versionCode=170009008

Processes:
  u0_a234 11599 com.coloros.smartsidebar:edgepanel
  u0_a234 12974 com.coloros.smartsidebar:ui

Settings:
  edge_panel_toggle=1
  edge_panel_float_bar_permanent=1
  edge_panel_os_float_bar_alpha=0
  sidebar_scene_region=1143 446 1217 603

Window:
  OplusOSEdgeFloatBar: 20x155 px, frame [1196,447]-[1216,602],
  ty=2314, fl=NOT_FOCUSABLE NOT_TOUCHABLE NOT_TOUCH_MODAL ...
  ColorOSEdgePanel: 1x1, ty=2314, NOT_FOCUSABLE NOT_TOUCHABLE
```
