# 平板界面适配调研（feature/tablet-ui）

日期：2026-09-19
范围：`app/`（Compose UI + Navigation 3，单 Activity），不含 syncthing 原生二进制与 service 层。

## TL;DR

- UI 已全部 Compose 化，没有 XML 布局，也没有 `layout-sw600dp` / `layout-land` 之类的资源目录。
- **目前没有任何基于窗口尺寸的适配**：全项目只有 2 处宽度上限约束（onboarding 按钮、设备 ID 对话框），其余内容全部 `fillMaxWidth`，平板上会横向拉伸。
- 已有 `isTelevision`（TV）适配分支，但那是输入形态维度，不是尺寸维度；`values-sw540dp` / `values-sw600dp` 里只剩死资源。
- 依赖里没有 `material3-adaptive`，也没有显式 `androidx.window`（`window` 仅作为 Compose UI 的 runtime 传递依赖存在，编译期不可用）。
- 建议路线：P0 窗口尺寸基础设施 → P1 Home 导航壳（Rail/常驻抽屉）→ P2 设置与 Home 的 list-detail 双栏 → P3 逐屏收尾（表单宽度、文件夹选择器、对话框）→ P4 状态保持与测试。

## 进展

- P0：`androidx.window:window-core` 依赖、`rememberWindowSizeClass()` / `adaptiveWidthClass` / `adaptiveHeightClass`（M3 断点）、`AdaptiveContent`（默认 840dp 上限、TV 不限宽）、设置页接入、断点单测。
- P1：Home 在 ≥600dp 宽度且非 TV 时使用 `NavigationRail`（手机与 TV 保持底栏），宽屏禁用 Pager 横滑；rail 接管 start/vertical insets。
- P2：设置页与 Home 都是 list-detail。设置：根列表为 list pane、子页为 detail pane；Home：列表为 list pane（440dp，容纳 rail）、文件夹/设备编辑器（以及文件夹选择器、自定义同步条件）为 detail pane。宽度 ≥840dp（expanded）且非 TV 时双栏，窄屏继续单栏推入；宽屏场景切换淡入淡出，详情之间的切换由 pane scaffold 内部动画处理。Home 无详情时是单栏（rail + 限宽内容）。
- P3：Onboarding 改用 WindowSizeClass（宽度/高度 compact）并限宽（整体 1200dp、正文 560dp）；设备 ID 对话框按宽度分栏（≥600dp，对话框上限 840dp）；文件夹选择器在宽屏变为“根目录侧栏 + 目录内容”；最近变更/日志/分享/状态页/首页内容统一 840dp 内容上限；删除 `values-sw540dp`/`sw600dp`/`xhdpi`/`xxhdpi` 死维度资源。
- P4：`MainActivity`/`SettingsActivity` 声明 configChanges —— 旋转与窗口缩放不再重建 Activity，`EditStateStore` 草稿不再丢失；新增 Compose UI 测试基建（`ui-test-junit4` + Robolectric NATIVE 图形）并覆盖 `AdaptiveContent` 在 400dp / 1280dp 下的宽度行为。
- 已知取舍：双栏时详情页仍显示返回箭头（与系统返回“先收起详情”的行为一致）；Home 的抽屉在列表栏内打开（只覆盖 pane）；折叠屏/铰链暂无专门处理，仅依赖窗口尺寸类。

## 1. 现状

### 1.1 架构

- 入口 `MainActivity.kt`：`setContent` + Navigation 3（`androidx.navigation3`，1.1.3），所有路由（Home / FolderEdit / DeviceEdit / FolderPicker / SyncConditions / Log / WebView）在这一层。
- `SettingsActivity.kt`：独立 Activity + 独立 `SettingsNavDisplay`（`SettingsNavigation.kt:109-146`），单栏推入式导航。
- `HomeScreen.kt`：`ModalNavigationDrawer`（:123）+ `TopAppBar`（:140）+ 底部 `NavigationBar`（:194）+ `HorizontalPager` 三页（:216，`beyondViewportPageCount = 2`）。
- 其余 Activity（Device/Folder/Log/RecentChanges/WebGui/WebView/Share/FolderPicker/SyncConditions/PhotoShoot/Onboarding）都是 Compose 宿主，绝大多数只是把内容包在 `Scaffold` 里。
- 无 XML layout 目录；`MaterialTheme` 全部在 Compose 侧，XML 主题（`values/themes.xml`）只保留窗口外观。

### 1.2 已有的“适配”痕迹

| 位置 | 逻辑 | 评价 |
| --- | --- | --- |
| `util/extensions.kt:5` `Configuration.isTelevision` | TV 分支 | 保留；**不能**被尺寸类替代（TV 往往是“宽度 expanded + 低高度”） |
| `SettingsScaffold.kt:59-64`、`RecentChangesScreen.kt:110,165` | TV 用普通 TopAppBar | 同上 |
| `OnboardingScaffold.kt:98-110` | TV 判断 + 按 `orientation` 手机竖屏/横屏两套布局 | 可迁移到 WindowSizeClass；`:643` 有 TODO “use window size class when material3.adaptive package is added” |
| `OnboardingScaffold.kt:503` | 按钮 `widthIn(max = 560.dp)` | 全项目仅有的两处宽度约束之一 |
| `DeviceIdDialog.kt:91-126` | 按 `orientation` 横屏改布局 | 平板上应改为按宽度判断（竖持宽屏平板也会触发） |
| `values-sw540dp/dimens.xml`、`values-sw600dp/dimens.xml` | 只覆盖 `material_divider_inset` 和 `welcome_title`/`slide_desc` | **死资源**：Compose 代码均未引用；`material_divider_inset` 只被 `values/themes.xml:16` 的 `android:listDivider`（遗留 ListView）引用 |
| `values-xhdpi|xxhdpi/dimens.xml` | 按密度定义 sp 字号（`welcome_title` 等） | 反模式且已无引用，可清理 |

### 1.3 Manifest / 窗口行为

- 无 `resizeableActivity`、无 `supports-screens`；targetSdk 36，默认支持多窗口 / 分屏 / ChromeOS / 折叠屏。
- 除 zxing 扫码页强锁竖屏（`AndroidManifest.xml:227-228`）外，没有方向锁；平板横竖屏切换会走 Activity 重建。
- `MainActivity` **没有** `configChanges`；`SettingsActivity/RecentChanges/WebGui/WebView` 声明了 `orientation|screenSize`。
- 重建风险：`MainActivity.kt:127-128` 的 `EditStateStore`（folder/device 编辑草稿）是 `remember {}`，不随 `rememberSerializable` 的导航栈保存 → 旋转/改变窗口尺寸时正在编辑的草稿会丢。属于既有缺陷，但平板旋转/自由窗口场景高发，建议本次一并修（见 P4）。

## 2. 差距清单（平板上的实际表现）

| 屏幕 | 关键位置 | 平板上的问题 | 建议 |
| --- | --- | --- | --- |
| Home 壳 | `HomeScreen.kt:123,140,194,216` | 底栏 + 横向 Pager 在宽屏上浪费空间；抽屉只能模态弹出；卡片整行拉到 2000dp+ | expanded 用 `NavigationRail`（或常驻抽屉）；medium 保持底栏；列表内容做 max-width 或多列 |
| Home 列表 | `HomeScreen.kt:369-403,450-477` | 分组卡片 `fillMaxWidth`，一行文本横跨整屏 | 双栏（列表 + 详情）或 `LazyVerticalStaggeredGrid` 两列；至少加内容上限 |
| 设置 | `SettingsNavigation.kt:115`、`SettingsScaffold.kt:110`、`SettingsRootScreen.kt` | 单栏推入，宽屏左侧大面积留白/拉伸 | expanded 用 Navigation3 `ListDetailSceneStrategy` 双栏（Root 在左、子页在右） |
| 文件夹/设备编辑 | `FolderEditScreen.kt:238`、`DeviceEditContent.kt:90`、`FolderEditContent.kt:36`、`Common.kt:87-90` | 表单卡片全部 `fillMaxWidth`，输入框跨屏；顶栏返回键在双栏下语义不对 | 与列表组成 list-detail；或独立打开时限制内容宽度并居中（600–840dp） |
| 文件夹选择器 | `FolderPickerScreen.kt:127-221` | 单栏逐级进入，宽屏利用率低 | expanded 双栏：根/目录 + 当前目录内容 |
| 最近变更 | `RecentChangesScreen.kt:153` | 单列长列表，整行拉伸 | max-width 或两列 |
| 日志 | `LogScreen.kt` | 等宽长文本整屏宽 | max-width；宽屏可并排显示时间与消息（已有分段可复用） |
| 状态页 | `StatusPage.kt:140` | 卡片全宽拉伸 | 内容上限；expanded 可用两列卡片 |
| 分享 | `ShareScreen.kt:127-160` | 表单全宽 | 同编辑页，限制内容宽度 |
| 抽屉 | `AppDrawer.kt:75`（`ModalDrawerSheet`） | 宽屏应常驻/可停靠 | `PermanentNavigationDrawer` / `DismissibleNavigationDrawer` 按宽度切换 |
| 对话框 | `DeviceIdDialog.kt:91` | 按方向而非宽度判断；平台 AlertDialog 默认最大宽度已够用 | 改宽度类判断，平板可常显左右分栏 |
| Onboarding | `OnboardingScaffold.kt:98-110,643` | 手写 compact 判断（container 最小边 ≤ 480dp）；横屏 2:3 分栏会随宽度继续拉伸 | 用 WindowSizeClass；限制插画/文本最大宽度；可顺手删 TODO |

## 3. 技术选型

1. **`androidx.window:window`（1.5.0 / 已发布 1.6.0-alpha05）**
   - 提供 `androidx.window.core.layout.WindowSizeClass`（width/height 的 compact/medium/expanded）与 `WindowInfoTracker`。
   - 在 Compose 里可从 `LocalWindowInfo.current.containerDpSize` 计算，天然支持多窗口/分屏。
   - 当前工程不能直接 `import`：`window` 只是 `compose-ui 1.11.3` 的 **runtime** 传递依赖（`ui-android-1.11.3.module` 只在 `androidRuntimeElements` 里声明），需要显式加依赖或在 P0 选择方案 2。

2. **`androidx.compose.material3.adaptive:adaptive-navigation3`（当前稳定版 1.3.0）**
   - 直接服务 Navigation 3：`ListDetailSceneStrategy` / `SupportingPaneSceneStrategy` / `ThreePaneScaffoldScene`（已核对 1.3.0 aar 的类）。
   - 传递依赖会带上 `adaptive`、`adaptive-layout`、`adaptive-navigation` 与 `androidx.window:window:1.5.0`；`currentWindowAdaptiveInfo()` 可直接拿窗口尺寸类。
   - 兼容性核对：1.3.0 依赖声明为 `navigation3-ui 1.0.0`，但字节码引用的是 `androidx.navigation3.scene.*`（与工程的 1.1.3 一致），集成时跑一次编译/回归即可确认；无需升级现有 Nav3。
   - 与 `material3 1.4.0` 不冲突（adaptive 只依赖 foundation/ui/window）。

3. **备选：`BoxWithConstraints` / `LocalConfiguration.screenWidthDp` 手写判断**
   - 改动最小，但难以覆盖分屏/折叠/多窗口的正确宽度，且 `screenWidthDp` 在自由窗口下不含实际容器信息。只建议用于极少量局部场景。

选型建议：**P0 先加 `adaptive-navigation3:1.3.0`**（顺带解决 window 可用性），全项目统一一个 `LocalWindowSizeClass`（或直接用 `currentWindowAdaptiveInfo()`），不要在业务代码里散落 `LocalConfiguration` 判断。

## 4. 分阶段建议

### P0 基础设施
- 添加依赖与版本目录条目；封装 `LocalWindowSizeClass` / `rememberWindowSizeClass()`。
- 引入统一的“内容最大宽度”组件（如 `AdaptiveContent(maxWidth = 840.dp)`：宽屏居中、窄屏全宽、TV 不限宽），先在设置页替换裸 `fillMaxWidth`。
- 编辑页（folder/device）暂不单独限宽：`Scaffold` 的 FAB 是屏幕级锚定的，内容居中后 FAB 会脱离内容柱，留到 P2 与分栏一起处理。
- 建立测试矩阵：平板模拟器（可调整尺寸）、折叠屏、TV（回归）、手机横屏、分屏模式。

### P1 Home 导航壳
- 宽度类切换：compact → 现状（ModalDrawer + NavigationBar + Pager）；medium → NavigationBar 或 Rail；expanded → `NavigationRail`（或常驻抽屉）+ 内容区。
- expanded 时禁用 Pager 的横向手势或直接去掉 Pager，改用直接切页。
- FAB 保持在内容区右下（Rail 布局下位置自然正确）。

### P2 双栏（list-detail）
- 设置：`SettingsNavDisplay` 接入 `ListDetailSceneStrategy`，Root 为列表栏，子页为详情栏；compact 自动回退单栏推入（保留现有的过场动画）。
- Home：文件夹/设备列表作为列表栏，`FolderEdit`/`DeviceEdit` 作为详情栏；编辑草稿已由 `EditStateStore` 按路由保存，天然支持双栏来回切换。编辑页进入详情栏后再做内容限宽，FAB 随详情栏对齐。
- 文件夹选择器：expanded 时根列表与当前目录并排。

### P3 逐屏收尾
- Onboarding：替换 `isCompactOnboardingScreen()`（含 TODO）；横屏分栏加最大宽度；TV 分支保留。
- `DeviceIdDialog`：方向判断改宽度判断，平板直接左右分栏。
- 列表类屏幕（RecentChanges / Log / Status）：内容上限或宽屏多列。
- 清理死资源：`values-sw540dp`、`values-sw600dp` 的非 `material_divider_inset` 条目与 `values-xhdpi|xxhdpi/dimens.xml`；若保留 `sw600dp`，用于真实需要的 XML 尺寸（如内容宽度）。

### P4 健壮性
- 修 `EditStateStore` 在配置变更下丢草稿的问题：改为 Activity 级 ViewModel 或 `rememberSaveable` 可序列化的 holder 存储；若选择给 `MainActivity` 加 `configChanges`，需同时确认 Compose 状态与 Locale/尺寸变化的行为，不推荐作为默认方案。
- 键盘/D-pad 焦点、TV 的 `FocusRequester` 逻辑在双栏下回归。
- 自由窗口拉伸时的连续性（拖动分界线不崩溃、无重复状态）。

## 5. 风险与注意

- **TV 不是平板**：TV 常见为宽而矮的窗口，尺寸类会把它判成 expanded/compact 混合，必须保留 `isTelevision` 分支，或联合判断 height size class。
- **Nav3 场景与现有转场**：`NavDisplay` 的 `transitionSpec`/`predictivePopTransitionSpec`（`AppNavDisplay.kt`、`SettingsNavigation.kt`）在 scene 策略接管后可能被绕过，双栏进退场需要单独设计（建议双栏时禁用滑动转场）。
- **AMOLED 主题**：卡片描边、纯黑底在双栏布局下需目视回归（`Common.kt:41-75`）。
- **依赖版本**：`adaptive-navigation3:1.3.0` 传递 `adaptive-navigation [1.3.0]`（严格版本区间），与未来其它 adaptive 依赖同升同降；升级时注意。
- **产品决策**：双栏里“新建文件夹/设备”的落点（列表栏还是详情栏）、抽屉是否常驻，需要先定一条一致规则。

## 6. 测试建议

- 现有测试只有 JVM/Robolectric 服务层与少量纯逻辑测试，没有 Compose UI 测试设施。
- 可用 `robolectric 4.16` + `androidx.compose.ui:ui-test-junit4` 做“不同容器宽度下关键布局断言”（如 600dp 出现 Rail、双栏可见性），成本低、进 CI。
- 手动验证：模拟器的 Resizable/Tablet 尺寸、`adb shell wm size` 切换、分屏拖拽、折叠屏合拢/展开、TV 至少过一遍主流程。

## 附录：关键文件

- 导航与壳：`app/src/main/java/com/nutomic/syncthingandroid/ui/nav/AppNavDisplay.kt`、`AppRoute.kt`、`activities/MainActivity.kt`、`activities/SettingsActivity.kt`
- Home：`ui/screens/home/HomeScreen.kt`、`AppDrawer.kt`、`HomeGroupCard.kt`、`FolderRow.kt`、`DeviceRow.kt`
- 设置：`ui/screens/settings/SettingsNavigation.kt`、`SettingsScaffold.kt`、`SettingsRootScreen.kt`
- 编辑：`ui/screens/folder/FolderEditScreen.kt`、`ui/screens/folder/FolderEditContent.kt`、`ui/screens/device/DeviceEditScreen.kt`、`ui/screens/device/DeviceEditContent.kt`
- 通用组件：`ui/components/Common.kt`（`AppCard`/`FormCard`/`ToggleRow`/`ClickRow`/`EmptyListHint`）
- 资源：`app/src/main/res/values{,-sw540dp,-sw600dp,-xhdpi,-xxhdpi}/dimens.xml`、`values/themes.xml`
