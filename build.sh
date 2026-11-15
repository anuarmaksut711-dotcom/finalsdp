#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$project_dir/find-java.sh"
stage_dir=$(mktemp -d "${TMPDIR:-/tmp}/digital-ecosystem.XXXXXX")
trap 'rm -rf "$stage_dir"' EXIT
trap 'exit 1' HUP INT TERM

mkdir "$stage_dir/classes"
"$javac_cmd" -encoding UTF-8 --release 17 -d "$stage_dir/classes" "$project_dir"/src/ecosystem/*.java
"$jar_cmd" --create --file "$stage_dir/digital-ecosystem.jar" --main-class ecosystem.App -C "$stage_dir/classes" .
mv -f "$stage_dir/digital-ecosystem.jar" "$project_dir/digital-ecosystem.jar"
printf 'Build successful: %s\n' "$project_dir/digital-ecosystem.jar"
