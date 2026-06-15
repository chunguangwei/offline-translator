#!/usr/bin/env bash
# 译人 iOS App Store 归档脚本。
#
# 前提（缺一不可，均需「付费 Apple Developer Program 账号」生效后才有）：
#   - Apple Distribution 证书（钥匙串里目前只有 Apple Development，缺它会签名失败）
#   - 该 App ID 的 App Store 描述文件（自动签名 -allowProvisioningUpdates 会拉取/创建）
#   - Xcode 已登录该 Apple ID（Settings → Accounts）
#
# 用法：
#   ./archive-appstore.sh                         # 正式版（不随包模型，用户应用内下载）
#   ./archive-appstore.sh --bundle-model [路径]   # 审核专用（随包模型，reviewer 开箱即用，IPA ~2.4G+）
#                                                 # 路径默认 /tmp/gemma-4-E2B-it.litertlm
#
# 上传：导出的 ipa 用 Transporter.app 拖入，或
#   xcrun altool --upload-app -f build/appstore/*.ipa -t ios -u <Apple ID> -p <app-专用密码>
set -euo pipefail
cd "$(dirname "$0")"

TEAM="L35RLT89XN"
BUNDLE_MODEL=0
MODEL_SRC="/tmp/gemma-4-E2B-it.litertlm"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bundle-model) BUNDLE_MODEL=1; shift; [[ $# -gt 0 && "$1" != --* ]] && { MODEL_SRC="$1"; shift; } || true ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

# 1) 随包模型开关（仅审核专用构建用；正式版保持 BundledModels/ 只有 .gitkeep）
rm -f BundledModels/*.litertlm
if [[ "$BUNDLE_MODEL" == "1" ]]; then
  [[ -f "$MODEL_SRC" ]] || { echo "找不到模型文件: $MODEL_SRC"; exit 1; }
  echo "→ 随包模型: $MODEL_SRC ($(du -h "$MODEL_SRC" | cut -f1))"
  cp "$MODEL_SRC" BundledModels/
fi

# 2) 生成工程
xcodegen generate

# 3) Archive（Release）
xcodebuild -project Yiren.xcodeproj -scheme Yiren \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath build/Yiren.xcarchive \
  DEVELOPMENT_TEAM="$TEAM" \
  -allowProvisioningUpdates \
  archive

# 4) 导出 App Store ipa
xcodebuild -exportArchive \
  -archivePath build/Yiren.xcarchive \
  -exportOptionsPlist ExportOptions-appstore.plist \
  -exportPath build/appstore \
  -allowProvisioningUpdates

# 5) 收尾：清掉随包模型，别污染后续正式构建
rm -f BundledModels/*.litertlm

echo ""
echo "✅ 完成。ipa 在 ios/build/appstore/ 下。"
echo "   用 Transporter.app 拖入上传，或 xcrun altool/notarytool 上传到 App Store Connect。"
