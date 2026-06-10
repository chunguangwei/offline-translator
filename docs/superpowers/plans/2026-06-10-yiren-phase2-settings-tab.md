# 译人 阶段 2：设置 Tab + 模型管理入口 实现计划

> 隶属设计：`docs/superpowers/specs/2026-06-09-yiren-restructure-design.md` 阶段 2。
> 验证门：`./gradlew :app:assembleDebug` 编译通过 + 真机冒烟。提交：真机验证前不入库。

**Goal:** 把现有完整的 `SettingsScreen` 挂到「设置」Tab，并在其中加「模型管理」入口跳转到 `ModelsScreen`（下载/启用/删除）；翻译页移除右上角临时模型图标（改由顶部缺失横幅的「去下载」+ 设置 Tab 承载）。

**Architecture:** `SettingsScreen` 已自带主题/语言/模型源/后端/更新/隐私，仅缺「模型下载列表」入口。给它加 `onOpenModels` 回调与一个「模型管理」section；AppShell 的 Settings 目的地从占位换成真实 `SettingsScreen(onOpenModels = 导航到 Models)`。

---

### Task 1: SettingsScreen 加「模型管理」入口

**Files:**
- Modify: `app/src/main/java/com/offlinetranslator/app/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml` + `values-en/strings.xml`

- [ ] Step 1: 字符串（中）：在 `settings_model_source` 相关串附近追加
```xml
    <string name="settings_model_manage">模型管理</string>
    <string name="settings_model_manage_action">下载 / 管理模型</string>
```
（英）
```xml
    <string name="settings_model_manage">Models</string>
    <string name="settings_model_manage_action">Download / manage models</string>
```

- [ ] Step 2: `SettingsScreen` 签名加 `onOpenModels`
```kotlin
fun SettingsScreen(
    padding: PaddingValues,
    onOpenModels: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
```

- [ ] Step 3: 在「模型源」section（`settings_model_source` 那个 SettingSection 之前）插入「模型管理」section
```kotlin
        SettingSection(title = stringResource(R.string.settings_model_manage)) {
            Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_model_manage_action))
            }
        }
```
（`Button`/`Modifier`/`fillMaxWidth`/`stringResource` 均已 import。）

- [ ] Step 4: 编译 `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL

---

### Task 2: AppShell 挂真实 Settings

**Files:** Modify `app/src/main/java/com/offlinetranslator/app/feature/shell/AppShell.kt`

- [ ] Step 1: import `com.offlinetranslator.app.feature.settings.SettingsScreen`
- [ ] Step 2: 把 `composable(Route.Settings.path) { PlaceholderScreen(innerPadding) }` 改为
```kotlin
            composable(Route.Settings.path) {
                SettingsScreen(
                    padding = innerPadding,
                    onOpenModels = { nav.navigate(Route.Models.path) { launchSingleTop = true } },
                )
            }
```
- [ ] Step 3: 编译 → BUILD SUCCESSFUL

---

### Task 3: 翻译页移除右上角临时模型图标

**Files:** Modify `app/src/main/java/com/offlinetranslator/app/feature/translate/TranslateScreen.kt`

- [ ] Step 1: 删除标题 Row 里的 `IconButton(onClick = onOpenModels){ Icon(Icons.Rounded.Widgets, ...) }`，让标题 Column 占满整行（保留 `onOpenModels` 参数——顶部缺失横幅的「去下载」仍用它）。把标题 Row 简化为直接放标题 Column（去掉外层 Row/IconButton）。
- [ ] Step 2: 删除不再使用的 `import androidx.compose.material.icons.rounded.Widgets`（若编译报未使用可留，但建议删）。
- [ ] Step 3: 编译 → BUILD SUCCESSFUL

---

### Task 4: 出包 + 冒烟

- [ ] `./gradlew :app:assembleDebug` → `cp .../app-debug.apk ~/Desktop/译人-阶段2-debug.apk`
- [ ] 冒烟：设置 Tab 显示真实设置；点「下载/管理模型」进 ModelsScreen；主题/语言切换生效；翻译页右上角无多余图标，缺失时仍有顶部「去下载」横幅。

## Self-Review
- 设置 Tab 真实化 ✅ Task2；模型下载入口入设置 ✅ Task1+2；翻译页清理 ✅ Task3。
- 命名一致：`onOpenModels` 在 Settings/Translate/AppShell 三处一致。
- 历史 Tab 仍占位（阶段 4）；问答仍占位（阶段 3）。
