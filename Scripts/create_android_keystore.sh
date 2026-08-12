#!/bin/zsh
set -euo pipefail

destination="/Users/Shared/MojiKeystore"
keystore="$destination/moji-release.jks"
properties="${0:A:h:h}/keystore.properties"
recovery="$destination/RECOVERY.txt"
jdk_home="${JAVA_HOME:-/Users/Shared/MojiToolchains/jdk17-ascii/Contents/Home}"

mkdir -p "$destination"
chmod 700 "$destination"

if [[ ! -f "$keystore" ]]; then
  password="$(/usr/bin/openssl rand -hex 24)"
  "$jdk_home/bin/keytool" -genkeypair \
    -keystore "$keystore" \
    -storepass "$password" \
    -keypass "$password" \
    -alias moji \
    -keyalg RSA \
    -keysize 4096 \
    -validity 36500 \
    -dname "CN=Moji, OU=Mobile, O=Raydon, L=Tokyo, C=JP"
  {
    print "Moji Android release signing recovery"
    print "keystore=$keystore"
    print "alias=moji"
    print "password=$password"
  } > "$recovery"
  chmod 600 "$keystore" "$recovery"
else
  password="$(sed -n 's/^password=//p' "$recovery")"
fi

{
  print "storeFile=$keystore"
  print "storePassword=$password"
  print "keyAlias=moji"
  print "keyPassword=$password"
} > "$properties"
chmod 600 "$properties"

print "Android release keystore ready: $keystore"
"$jdk_home/bin/keytool" -list -v -keystore "$keystore" -storepass "$password" -alias moji \
  | sed -n '/SHA256:/p'
