# 启动图远程配置（不发版换图）

App（Android 与 iOS）启动时会后台拉取本目录的 `splash.json`，
**改这个文件并推到 main 分支即可更换启动图，无需发版**。

## 配置格式

```json
{
  "enabled": true,
  "imageUrl": "https://cdn.jsdelivr.net/gh/chunguangwei/offline-translator@main/branding/splash/your-image.png"
}
```

| 字段 | 说明 |
| --- | --- |
| `enabled` | `true` 显示远程图；`false` 回到内置的眨眼 logo 动画 |
| `imageUrl` | 图片直链（建议 9:19.5 竖图，PNG/JPG，≤1MB；图片也可以直接放本目录用 jsDelivr 链接） |

## 生效时机与兜底

- 拉取是**后台静默**进行的，本次启动用上次缓存 → 改完配置后**用户下次启动**见效。
- App 端按 jsDelivr CDN（国内可达）→ raw.githubusercontent.com 顺序取配置；
  jsDelivr 对 `@main` 有 ~12 小时缓存，换图最迟半天到达全部用户。
- 拉取失败 / 断网 / `enabled=false` → 显示内置眨眼 logo 动画，启动页永不空白、永不等网络。

## 换图步骤

1. 把新图放进本目录（如 `2026-spring.png`），或任意可公网直链的图床。
2. 改 `splash.json`：`enabled: true`，`imageUrl` 指向新图。
3. 提交推送到 `main`。完事。
