#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# The tag is exactly the version (no "v", no hash) and each release carries one APK,
# so Obtainium needs no settings. Releases are built locally; there is no CI.
[ -f nextup.properties ] || echo "!! nextup.properties missing: this build will not send progress to Next Up." >&2

trap 'status=$?; if [ $status -ne 0 ]; then
  echo "!! release.sh FAILED (exit $status). Nothing was published." >&2
fi' EXIT

gradle_file="app/build.gradle.kts"
current=$(grep -oP 'versionName = "\K[^"]+' "$gradle_file")
code=$(grep -oP 'versionCode = \K[0-9]+' "$gradle_file")
next="${current%.*}.$(( ${current##*.} + 1 ))"
echo "==> tankobon $current -> $next"

sed -i "s/versionName = \"$current\"/versionName = \"$next\"/" "$gradle_file"
sed -i "s/versionCode = $code/versionCode = $(( code + 1 ))/" "$gradle_file"

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease

apk="app/build/outputs/apk/release/app-release.apk"
sdk="${ANDROID_HOME:-$HOME/development/android-sdk}"
aapt2=$(ls -d "$sdk"/build-tools/*/aapt2 | sort -V | tail -1)

badging=$("$aapt2" dump badging "$apk")
built=$(printf '%s\n' "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | sed -n 1p)
[ "$built" = "$next" ] || { echo "APK says $built, expected $next" >&2; exit 1; }

# R8 strips what it cannot trace a call to, and the system builds the activity
# by name. A missing keep rule installs fine and fails only at launch.
dexdump=$(ls -d "$sdk"/build-tools/*/dexdump | sort -V | tail -1)
work=$(mktemp -d); trap 'rm -rf "$work"' RETURN
unzip -o -q "$apk" "classes*.dex" -d "$work"
for class in MainActivity; do
  found=$(for d in "$work"/*.dex; do "$dexdump" "$d" 2>/dev/null; done \
    | grep -c "Lcom/tankobon/app/$class;" || true)
  [ "$found" -gt 0 ] || { echo "R8 stripped $class" >&2; exit 1; }
done
rm -rf "$work"

git add -A
git commit -m "Release $next"
git push origin HEAD

release_apk="tankobon-$next.apk"
cp "$apk" "$release_apk"
gh release create "$next" "$release_apk" \
  --title "tankobon $next" \
  --notes "$(git log -1 --skip=1 --pretty=%s)" \
  --target "$(git rev-parse HEAD)"
rm -f "$release_apk"

echo "==> APK verified as $next"
gh release view "$next" --json url -q .url
