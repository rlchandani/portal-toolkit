#!/usr/bin/env bash
# Publishes a Portal Toolkit wireless (OTA) update as a GitHub Release.
#
#   tools/scripts/ota-release.sh [--dry-run] [--notes "One-line what's new"]
#
# Spec: docs/plans/ota-github-releases-2026-11-08.md (portal repo). The phone installs
# releases/latest/download/portal-toolkit-latest.json, so this script:
#   1. guards: clean tree, on feat/toolkit-1.7.10 or main, versionCode > the published one,
#      tag not taken, HEAD already on the GitHub repo (publish only)
#   2. runs the gate (unit tests, debug build, androidTest compile)
#   3. checks the debug APK: package, versionCode, and that it is signed only by the pinned key
#   4. writes portal-toolkit-latest.json (sha256, size, apkUrl, ...)
#   5. gh release create toolkit-v<versionName> --latest with portal-toolkit.apk + the json
#   6. downloads both back from the public releases/latest/download/ URLs and re-verifies
# --dry-run does everything except step 5 and 6 (it verifies the staged files instead).
# Never prints credentials: it only ever shows versions, digests, sizes and public URLs.
set -euo pipefail

readonly REPO="rlchandani/portal-toolkit"
readonly PACKAGE="com.portalinstaller"
readonly PINNED_SHA256="4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6"
readonly APK_ASSET="portal-toolkit.apk"
readonly JSON_ASSET="portal-toolkit-latest.json"
readonly TAG_PREFIX="toolkit-v"
readonly ALLOWED_BRANCHES=("feat/toolkit-1.7.10" "main")
readonly LATEST_BASE="https://github.com/${REPO}/releases/latest/download"
readonly BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0}"
readonly APKSIGNER="${BUILD_TOOLS}/apksigner"
readonly AAPT2="${BUILD_TOOLS}/aapt2"
readonly MANIFEST_SCHEMA=1
readonly DOWNLOAD_ATTEMPTS=6
readonly DOWNLOAD_WAIT_SECONDS=10
export JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home}"

dry_run=0
notes=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) dry_run=1; shift ;;
    --notes) notes="${2:?--notes needs a value}"; shift 2 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

step() { printf '\n==> %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }
size_of() { wc -c < "$1" | tr -d ' '; }
json_field() { python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$1" "$2"; }

# Exactly one signer, and it is the pinned certificate.
check_signer() {
  local apk="$1" digests
  digests="$("$APKSIGNER" verify --print-certs "$apk" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')"
  [[ -n "$digests" ]] || fail "apksigner found no signer in $(basename "$apk")"
  [[ "$(printf '%s\n' "$digests" | wc -l | tr -d ' ')" == "1" ]] || fail "$(basename "$apk") has more than one signer"
  [[ "$digests" == "$PINNED_SHA256" ]] || fail "$(basename "$apk") is signed by $digests, not the pinned key $PINNED_SHA256"
  echo "Signer SHA-256: $digests (pinned)"
}

check_badging() {
  local apk="$1" badging
  badging="$("$AAPT2" dump badging "$apk")" || fail "aapt2 couldn't read $(basename "$apk")"
  badging="${badging%%$'\n'*}"  # first line only; piping to head would SIGPIPE under pipefail
  [[ "$badging" == *"name='${PACKAGE}'"* ]] || fail "APK package is not ${PACKAGE}"
  [[ "$badging" == *"versionCode='${version_code}'"* ]] || fail "APK versionCode is not ${version_code}"
  echo "APK: ${PACKAGE} versionCode ${version_code}"
}

cd "$(git rev-parse --show-toplevel)"
for tool in "$APKSIGNER" "$AAPT2"; do [[ -x "$tool" ]] || fail "Missing $tool (set ANDROID_BUILD_TOOLS)"; done
command -v gh >/dev/null || fail "Missing gh (GitHub CLI)"
command -v python3 >/dev/null || fail "Missing python3"

step "Guards"
[[ -z "$(git status --porcelain)" ]] || fail "Working tree is not clean; commit or stash first"
branch="$(git rev-parse --abbrev-ref HEAD)"
allowed=0
for b in "${ALLOWED_BRANCHES[@]}"; do [[ "$branch" == "$b" ]] && allowed=1; done
[[ "$allowed" == 1 ]] || fail "Release from ${ALLOWED_BRANCHES[*]} only (on $branch)"
gradle_file="app/build.gradle.kts"
version_code="$(sed -n 's/^ *versionCode = \([0-9][0-9]*\).*/\1/p' "$gradle_file" | head -1)"
version_name="$(sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' "$gradle_file" | head -1)"
min_sdk="$(sed -n 's/^ *minSdk = \([0-9][0-9]*\).*/\1/p' "$gradle_file" | head -1)"
[[ -n "$version_code" && -n "$version_name" && -n "$min_sdk" ]] || fail "Couldn't read versionCode/versionName/minSdk from $gradle_file"
[[ "$version_name" =~ ^[0-9A-Za-z._-]+$ ]] || fail "versionName '$version_name' isn't tag-safe"
tag="${TAG_PREFIX}${version_name}"
apk_url="https://github.com/${REPO}/releases/download/${tag}/${APK_ASSET}"
[[ -n "$notes" ]] || notes="$(git log -1 --format=%s)"
notes="$(printf '%s' "$notes" | tr '\n' ' ')"
head_sha="$(git rev-parse HEAD)"
echo "Branch $branch at ${head_sha:0:12}; Portal Toolkit $version_name (versionCode $version_code)"

work="$(mktemp -d "${TMPDIR:-/tmp}/ota-release.XXXXXX")"
trap 'rm -rf "$work"' EXIT
published_code=0
http_code="$(curl -sSL -o "$work/published.json" -w '%{http_code}' "${LATEST_BASE}/${JSON_ASSET}" || true)"
case "$http_code" in
  200) published_code="$(json_field "$work/published.json" versionCode)" ;;
  404) echo "No release published yet" ;;
  *) fail "Couldn't read the published manifest (HTTP $http_code)" ;;
esac
echo "Published versionCode: $published_code"
(( version_code > published_code )) || fail "versionCode $version_code must be higher than the published $published_code: bump the version"
if gh release view "$tag" --repo "$REPO" >/dev/null 2>&1; then fail "Release $tag already exists"; fi
if [[ "$dry_run" == 0 ]]; then
  remote="$(git remote -v | awk '$3 == "(push)" {print $1, $2}' |
    grep -E " (https://|git@)github\.com[/:]${REPO}(\.git)?$" | awk '{print $1; exit}' || true)"
  [[ -n "$remote" ]] || fail "No git remote points at ${REPO}"
  git fetch -q "$remote" "$branch" || fail "Couldn't fetch $branch from $remote"
  git merge-base --is-ancestor HEAD FETCH_HEAD || fail "Push $branch to $remote first: the release tag points at HEAD"
fi

step "Gate"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin \
  --no-daemon -q -Dorg.gradle.jvmargs=-Xmx4g

step "Build and verify the APK"
./gradlew :app:assembleDebug --no-daemon -q -Dorg.gradle.jvmargs=-Xmx4g
built="app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$built" ]] || fail "Missing $built"
cp "$built" "$work/$APK_ASSET"
check_signer "$work/$APK_ASSET"
check_badging "$work/$APK_ASSET"
apk_sha="$(sha256_of "$work/$APK_ASSET")"
apk_size="$(size_of "$work/$APK_ASSET")"
echo "SHA-256 $apk_sha, $apk_size bytes"

step "Write $JSON_ASSET"
# Values travel as argv (never interpolated into code), so quotes in the notes can't break the JSON.
python3 -I - "$work/$JSON_ASSET" "$MANIFEST_SCHEMA" "$PACKAGE" "$version_code" "$version_name" \
  "$apk_sha" "$apk_size" "$apk_url" "$min_sdk" "$notes" <<'PY'
import json, sys
path, schema, package, code, name, sha, size, url, min_sdk, notes = sys.argv[1:]
manifest = {"schema": int(schema), "packageName": package, "versionCode": int(code), "versionName": name,
            "sha256": sha, "size": int(size), "apkUrl": url, "minSdk": int(min_sdk), "notes": notes.strip()}
with open(path, "w") as out:
    json.dump(manifest, out, indent=2)
    out.write("\n")
PY

verify_pair() {
  local dir="$1"
  [[ "$(sha256_of "$dir/$APK_ASSET")" == "$apk_sha" ]] || fail "$APK_ASSET SHA-256 differs from the manifest"
  [[ "$(size_of "$dir/$APK_ASSET")" == "$apk_size" ]] || fail "$APK_ASSET size differs from the manifest"
  [[ "$(json_field "$dir/$JSON_ASSET" sha256)" == "$apk_sha" ]] || fail "$JSON_ASSET sha256 is wrong"
  [[ "$(json_field "$dir/$JSON_ASSET" versionCode)" == "$version_code" ]] || fail "$JSON_ASSET versionCode is wrong"
  [[ "$(json_field "$dir/$JSON_ASSET" apkUrl)" == "$apk_url" ]] || fail "$JSON_ASSET apkUrl is wrong"
  check_signer "$dir/$APK_ASSET"
}

if [[ "$dry_run" == 1 ]]; then
  step "Dry run: verify the staged assets"
  verify_pair "$work"
  echo "Manifest:"; cat "$work/$JSON_ASSET"
  echo "Dry run OK. Would run: gh release create $tag --repo $REPO --latest --target ${head_sha:0:12} $APK_ASSET $JSON_ASSET"
  exit 0
fi

step "Publish $tag"
gh release create "$tag" "$work/$APK_ASSET" "$work/$JSON_ASSET" --repo "$REPO" --latest \
  --target "$head_sha" --title "Portal Toolkit $version_name" --notes "$notes"

step "Download back from ${LATEST_BASE}/ and re-verify"
mkdir "$work/back"
for attempt in $(seq 1 "$DOWNLOAD_ATTEMPTS"); do
  if curl -fsSL -o "$work/back/$JSON_ASSET" "${LATEST_BASE}/${JSON_ASSET}" &&
     [[ "$(json_field "$work/back/$JSON_ASSET" versionCode)" == "$version_code" ]]; then break; fi
  [[ "$attempt" == "$DOWNLOAD_ATTEMPTS" ]] && fail "The latest release doesn't serve versionCode $version_code yet"
  sleep "$DOWNLOAD_WAIT_SECONDS"
done
cmp -s "$work/back/$JSON_ASSET" "$work/$JSON_ASSET" || fail "Downloaded $JSON_ASSET differs from the uploaded one"
curl -fsSL -o "$work/back/$APK_ASSET" "${LATEST_BASE}/${APK_ASSET}"
verify_pair "$work/back"
echo "Published Portal Toolkit $version_name: phones update from ${LATEST_BASE}/${JSON_ASSET}"
