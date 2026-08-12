#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
project_root="${script_dir:h}"
cd "$project_root"

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

print "==> Moji $version_name ($version_code): core tests"
./gradlew :core:testAndroidHostTest --no-daemon -q

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
git archive --format=zip --output="$source_dist/Moji-v${version_name}-build${version_code}-source.zip" HEAD
(
  cd dist
  find Android iOS Source -type f -maxdepth 2 -print0 \
    | sort -z \
    | xargs -0 shasum -a 256 > SHA256SUMS
)

print "==> Release artifacts are under $project_root/dist"
