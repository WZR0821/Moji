#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
project_root="${script_dir:h}"
cd "$project_root"

# The binaries and git archive must describe the same exact source tree.
if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
  print -u2 "发布已停止：请先审查并提交工作区修改，确保安装包与源码归档一致。"
  exit 1
fi
release_commit="$(git rev-parse HEAD)"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "/Users/Shared/MojiToolchains/jdk17-ascii/Contents/Home" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  print -u2 "需要 JDK 17；请设置 JAVA_HOME。"
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -f keystore.properties ]]; then
  Scripts/create_android_keystore.sh
fi

version_name="$(sed -n 's/^versionName=//p' version.properties)"
version_code="$(sed -n 's/^versionCode=//p' version.properties)"
android_dist="$project_root/dist/Android"
ios_dist="$project_root/dist/iOS"
source_dist="$project_root/dist/Source"
mkdir -p "$android_dist" "$ios_dist" "$source_dist"

for artifact in \
  "$android_dist/Moji-v${version_name}-build${version_code}-release.apk" \
  "$android_dist/Moji-v${version_name}-build${version_code}-release.aab" \
  "$ios_dist/Moji-v${version_name}-build${version_code}-unsigned.ipa" \
  "$source_dist/Moji-v${version_name}-build${version_code}-source.zip"; do
  if [[ -e "$artifact" ]]; then
    print -u2 "发布已停止：目标产物已经存在，请使用新的版本号或构建号。"
    exit 1
  fi
done

print "==> Moji $version_name ($version_code): core + Android tests"
./gradlew :core:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:lintRelease --no-daemon -q

print "==> Android release APK + AAB"
./gradlew :androidApp:assembleRelease :androidApp:bundleRelease --no-daemon -q
cp androidApp/build/outputs/apk/release/androidApp-release.apk \
  "$android_dist/Moji-v${version_name}-build${version_code}-release.apk"
cp androidApp/build/outputs/bundle/release/androidApp-release.aab \
  "$android_dist/Moji-v${version_name}-build${version_code}-release.aab"

print "==> iOS unsigned IPA"
DEVELOPER_DIR="${DEVELOPER_DIR:-$HOME/Downloads/Xcode.app/Contents/Developer}" \
  Scripts/build_unsigned_ipa.sh
cp "dist/Moji-v${version_name}-build${version_code}-unsigned.ipa" \
  "$ios_dist/Moji-v${version_name}-build${version_code}-unsigned.ipa"

print "==> source archive + checksums"
if [[ -n "$(git status --porcelain --untracked-files=all)" || "$(git rev-parse HEAD)" != "$release_commit" ]]; then
  print -u2 "发布已停止：构建期间源码发生变化，请重新审查后构建。"
  exit 1
fi
print -r -- "version=$version_name
build=$version_code
commit=$release_commit
built_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$source_dist/Moji-v${version_name}-build${version_code}-provenance.txt"
git archive --format=zip --output="$source_dist/Moji-v${version_name}-build${version_code}-source.zip" HEAD
(
  cd dist
  find Android iOS Source -type f -maxdepth 2 -print0 \
    | sort -z \
    | xargs -0 shasum -a 256 > SHA256SUMS
)

print "==> Release artifacts are under $project_root/dist"
