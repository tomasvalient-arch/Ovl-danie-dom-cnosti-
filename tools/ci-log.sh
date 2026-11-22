#!/bin/sh
# DOČASNÉ (na ladenie CI): pošle koniec logu kroku do vetvy ci-logs-<názov>, aby sa dal prečítať bez prihlásenia.
# Použitie: ci-log.sh <názov> <súbor s logom>
name="$1"
file="$2"
d=$(mktemp -d)
if [ -f "$file" ]; then tail -c 90000 "$file" > "$d/$name.log"; else echo "log chýba" > "$d/$name.log"; fi
cd "$d" || exit 0
git init -q
git config user.name ci
git config user.email ci@invalid
git checkout -q -b "ci-logs-$name"
git add .
git commit -q -m "log $name $GITHUB_RUN_ID"
git push -q -f "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "ci-logs-$name" || true
