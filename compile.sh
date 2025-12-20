#!/usr/bin/env bash

# Always pause before closing (even if we error)
pause_on_exit() {
  local code=$?
  echo
  if [[ $code -eq 0 ]]; then
    echo "Done."
  else
    echo "Script exited with code: $code"
  fi
  read -n 1 -s -r -p "Press any key to close..."
  echo
}
trap pause_on_exit EXIT

# Better error info
set -euo pipefail
trap 'echo; echo "ERROR on line $LINENO: $BASH_COMMAND"' ERR

# Always run from the directory where THIS script lives (so pom.xml/target are found)
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Optional:
#   ./build-one.sh MyCoolName   (sets base name for output file)
JAR_BASENAME="${1:-HuskHomesMenus}"

PLUGIN_YML="src/main/resources/plugin.yml"
if [[ ! -f "$PLUGIN_YML" ]]; then
  echo "Could not find $PLUGIN_YML"
  exit 1
fi

# Extract version from plugin.yml: version: 1.0.0
PLUGIN_VERSION="$(
  sed -n 's/^[[:space:]]*version:[[:space:]]*//p' "$PLUGIN_YML" \
    | head -n 1 \
    | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//'
)"
if [[ -z "${PLUGIN_VERSION:-}" ]]; then
  echo "Could not parse version from $PLUGIN_YML"
  exit 1
fi

# Make it filename-safe
SAFE_VER="$(echo "$PLUGIN_VERSION" | sed 's/[^A-Za-z0-9._-]/-/g')"

echo "Working directory: $(pwd)"
echo "plugin.yml version: $PLUGIN_VERSION"
echo

# Ask what to generate
echo "What do you want to generate?"
echo "  1) Normal jar (${JAR_BASENAME}-${SAFE_VER}.jar)"
echo "  2) Test jar   (${JAR_BASENAME}-${SAFE_VER}-test.jar)"
echo "  3) Both"
read -r -p "Choose 1/2/3: " CHOICE

case "${CHOICE:-}" in
  1|2|3) ;;
  *) echo "Invalid choice. Enter 1, 2, or 3."; exit 1 ;;
esac

# If choice is Normal or Both, ask about git push
DO_GIT_PUSH="n"
COMMIT_MSG=""
if [[ "$CHOICE" == "1" || "$CHOICE" == "3" ]]; then
  echo
  read -r -p "Do you want to push changes to git? (y/n): " DO_GIT_PUSH
  DO_GIT_PUSH="$(echo "${DO_GIT_PUSH:-n}" | tr '[:upper:]' '[:lower:]')"

  if [[ "$DO_GIT_PUSH" == "y" || "$DO_GIT_PUSH" == "yes" ]]; then
    # Make sure we're in a git repo
    if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      echo "Not a git repository (or git not available)."
      exit 1
    fi

    echo
    read -r -p 'Commit message (e.g., "Fix tpauto behavior"): ' COMMIT_MSG
    if [[ -z "${COMMIT_MSG:-}" ]]; then
      echo "Commit message cannot be empty."
      exit 1
    fi
  else
    DO_GIT_PUSH="n"
  fi
fi

echo
echo "Building once with Maven..."
mvn -q clean package

# Find the main built jar (skip sources/javadoc/original)
BUILT_JAR="$(ls -1t target/*.jar 2>/dev/null | grep -vE '(original-|sources|javadoc)' | head -n 1 || true)"
if [[ -z "${BUILT_JAR:-}" ]]; then
  echo "No built jar found in target/"
  exit 1
fi

OUT_DIR="target"
mkdir -p "$OUT_DIR"

NORMAL_JAR="${OUT_DIR}/${JAR_BASENAME}-${SAFE_VER}.jar"
TEST_JAR="${OUT_DIR}/${JAR_BASENAME}-${SAFE_VER}-test.jar"

copy_if_not_same() {
  local src="$1"
  local dest="$2"
  local src_abs dest_abs
  src_abs="$(cd "$(dirname "$src")" && pwd)/$(basename "$src")"
  dest_abs="$(cd "$(dirname "$dest")" && pwd)/$(basename "$dest")"

  if [[ "$src_abs" == "$dest_abs" ]]; then
    echo "Output name matches the built jar; leaving as-is:"
    echo "  $dest"
  else
    cp -f "$src" "$dest"
    echo "Created:"
    echo "  $dest"
  fi
}

echo
case "$CHOICE" in
  1)
    copy_if_not_same "$BUILT_JAR" "$NORMAL_JAR"
    ;;
  2)
    copy_if_not_same "$BUILT_JAR" "$TEST_JAR"
    ;;
  3)
    copy_if_not_same "$BUILT_JAR" "$NORMAL_JAR"
    copy_if_not_same "$BUILT_JAR" "$TEST_JAR"
    ;;
esac

# Git add/commit/push (only if Normal or Both and user said yes)
if [[ ("$CHOICE" == "1" || "$CHOICE" == "3") && ("$DO_GIT_PUSH" == "y" || "$DO_GIT_PUSH" == "yes") ]]; then
  echo
  echo "Pushing changes to git..."
  git add .
  git commit -m "$COMMIT_MSG"
  git push
fi
