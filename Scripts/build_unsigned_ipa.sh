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
marketing_version="$(sed -n 's/^versionName=//p' "$project_root/version.properties")"
build_number="$(sed -n 's/^versionCode=//p' "$project_root/version.properties")"

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
  MARKETING_VERSION="$marketing_version" \
  CURRENT_PROJECT_VERSION="$build_number" \
  build

app_path="$derived_data/Build/Products/Release-iphoneos/Moji.app"
if [[ ! -d "$app_path" ]]; then
  print -u2 "未找到构建产物：$app_path"
  exit 1
fi

ditto "$app_path" "$payload_dir/Moji.app"

# SideStore and AltStore decide which App Groups to register from the
# entitlements in each binary's code signature. A build with signing turned
# off carries none, so the re-signed app and its extensions would get no group
# and the widget and the share sheet could never reach the app's data. An
# ad-hoc signature carries the declared group; the installer replaces the
# signature and renames the group to group.com.raydon.minuteplan.<TEAMID>,
# which the app reads at runtime. Extensions are signed before the app that
# contains them.
widget_path="$payload_dir/Moji.app/PlugIns/MojiWidget.appex"
share_path="$payload_dir/Moji.app/PlugIns/MojiShare.appex"
for extension_path in "$widget_path" "$share_path"; do
  if [[ ! -d "$extension_path" ]]; then
    print -u2 "未找到扩展：$extension_path"
    exit 1
  fi
done
expected_group="group.com.raydon.minuteplan"
for target in widget share app; do
  if [[ "$target" == widget ]]; then
    bundle_path="$widget_path"
    entitlements_path="$project_root/MojiWidget/MojiWidget.entitlements"
  elif [[ "$target" == share ]]; then
    bundle_path="$share_path"
    entitlements_path="$project_root/MojiShare/MojiShare.entitlements"
  else
    bundle_path="$payload_dir/Moji.app"
    entitlements_path="$project_root/Moji/Moji.entitlements"
  fi
  /usr/bin/codesign --force --sign - --timestamp=none --generate-entitlement-der \
    --entitlements "$entitlements_path" "$bundle_path"
  embedded="$(/usr/bin/codesign -d --entitlements - --xml "$bundle_path" 2>/dev/null)"
  if [[ "$embedded" != *"<string>$expected_group</string>"* ]]; then
    print -u2 "签名里没有 App Group 声明：$bundle_path"
    exit 1
  fi
done
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
# Read the whole report before matching: with pipefail, `grep -q` quitting
# early can kill codesign with SIGPIPE and fail the check at random.
signature_info="$(/usr/bin/codesign -dv "$payload_dir/Moji.app" 2>&1 || true)"
if [[ -e "$payload_dir/Moji.app/embedded.mobileprovision" ]]; then
  print -u2 "警告：App 带有描述文件，不是给 SideStore 重签用的原始包。"
elif [[ "$signature_info" == *$'\n'"Signature=adhoc"$'\n'* ]]; then
  print "签名检查：临时签名，只用来带上 App Group 声明，安装时由 SideStore 重签（符合预期）"
else
  print -u2 "警告：App 不是临时签名。"
fi
