#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
project_root="${script_dir:h}"
project_file="$project_root/Moji.xcodeproj"
build_root="$project_root/build/unsigned"
derived_data="$build_root/DerivedData"
payload_dir="$build_root/Payload"
dist_dir="$project_root/dist"
latest_ipa_path="$dist_dir/Moji-unsigned.ipa"

if [[ -z "${DEVELOPER_DIR:-}" ]]; then
  for candidate in \
    "/Applications/Xcode.app/Contents/Developer" \
    "$HOME/Downloads/Xcode.app/Contents/Developer" \
    "$HOME/Applications/Xcode.app/Contents/Developer"; do
    if [[ -x "$candidate/usr/bin/xcodebuild" ]]; then
      export DEVELOPER_DIR="$candidate"
      break
    fi
  done
fi

if [[ -z "${DEVELOPER_DIR:-}" || ! -x "$DEVELOPER_DIR/usr/bin/xcodebuild" ]]; then
  print -u2 "未找到完整 Xcode。请设置 DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer"
  exit 1
fi

if [[ ! -d "$project_file" ]]; then
  print -u2 "缺少 $project_file，请先运行 XcodeGen 生成工程。"
  exit 1
fi

if [[ "$build_root" != "$project_root/build/unsigned" || "$dist_dir" != "$project_root/dist" ]]; then
  print -u2 "构建目录校验失败。"
  exit 1
fi

rm -rf "$build_root"
mkdir -p "$derived_data" "$payload_dir" "$dist_dir"

xcodebuild \
  -project "$project_file" \
  -scheme Moji \
  -configuration Release \
  -sdk iphoneos \
  -destination "generic/platform=iOS" \
  -derivedDataPath "$derived_data" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  build

app_path="$derived_data/Build/Products/Release-iphoneos/Moji.app"
if [[ ! -d "$app_path" ]]; then
  print -u2 "未找到构建产物：$app_path"
  exit 1
fi

ditto "$app_path" "$payload_dir/Moji.app"
app_info_plist="$payload_dir/Moji.app/Info.plist"
marketing_version=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$app_info_plist")
build_number=$(/usr/libexec/PlistBuddy -c "Print :CFBundleVersion" "$app_info_plist")
versioned_ipa_path="$dist_dir/Moji-v${marketing_version}-build${build_number}-unsigned.ipa"

rm -f "$versioned_ipa_path" "$latest_ipa_path"
(
  cd "$build_root"
  /usr/bin/zip -qry "$versioned_ipa_path" Payload
)
/usr/bin/ditto "$versioned_ipa_path" "$latest_ipa_path"

print "已输出版本化 IPA：$versioned_ipa_path"
print "最新版快捷文件：$latest_ipa_path"
/usr/bin/file "$payload_dir/Moji.app/Moji"
if /usr/bin/codesign -dv "$payload_dir/Moji.app" >/dev/null 2>&1; then
  print -u2 "警告：App 仍带有代码签名。"
else
  print "签名检查：未签名（符合预期）"
fi
