#!/usr/bin/env bash

# requires curl & jq

# upstreamCommit.sh --paper HASH
# flag: --paper HASH - (Optional) commit hash to compare between PaperMC/Paper (HASH...HEAD)

function getCommits() {
    curl -sSL -H "Accept: application/vnd.github.v3+json" "https://api.github.com/repos/$1/compare/$2...$3" \
        | jq -r '.commits[] | "'"$1"'@\(.sha[:8]) \(.commit.message | split("\r\n")[0] | split("\n")[0])" | sub("\\[ci( |-)skip]"; "[ci/skip]") | sub("\\(#(?<issue>\\d+)\\)"; "'"$1"'#\(.issue)")'
}

(
set -e
PS1="$"

paperHash=$(git diff gradle.properties | awk -F= '/^-paperCommit/{print $2}' | tr -d '[:space:]')

TEMP=$(getopt --long paper: -o "" -- "$@")
eval set -- "$TEMP"
while true; do
    case "$1" in
        --paper)
            paperHash="$2"
            shift 2
            ;;
        *)
            break
            ;;
    esac
done

paper=""
updated=""
logsuffix=""

if [ -n "$paperHash" ]; then
    newHash=$(git diff gradle.properties | awk -F= '/^\+paperCommit/{print $2}' | tr -d '[:space:]')
    paper=$(getCommits "PaperMC/Paper" "$paperHash" "${newHash:-HEAD}")

    if [ -n "$paper" ]; then
        updated="Paper"
        logsuffix="\n\nPaper Changes:\n$paper"
    fi
fi

disclaimer="Upstream has released updates that appear to apply and compile correctly"
log="Updated Upstream (${updated:-Paper})\n\n${disclaimer}${logsuffix}"

git add gradle.properties
git add */build.gradle.kts.patch

echo -e "$log" | git commit -F -

) || exit 1
