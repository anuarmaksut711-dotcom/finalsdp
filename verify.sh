#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
desktop=false
case "${1:-}" in
    '') ;;
    --desktop) desktop=true ;;
    *) printf '%s\n' 'Usage: sh verify.sh [--desktop]' >&2; exit 1 ;;
esac
sh "$project_dir/build.sh"
. "$project_dir/find-java.sh"
verification_dir="$project_dir/tmp/verification"
classes="$verification_dir/classes"
mkdir -p "$classes"
"$javac_cmd" -encoding UTF-8 --release 17 -cp "$project_dir/digital-ecosystem.jar" -d "$classes" \
    "$project_dir/docs/CliSmoke.java" "$project_dir/docs/HuntAnimationSmoke.java" \
    "$project_dir/docs/GameUiSmoke.java" "$project_dir/docs/UiSmoke.java" "$project_dir/docs/ExportSmoke.java" \
    "$project_dir/docs/PatternDemo.java"
classpath="$project_dir/digital-ecosystem.jar:$classes"
cd "$project_dir"
log="$verification_dir/results.txt"
: > "$log"
run_check() {
    if "$@" >> "$log" 2>&1; then
        return 0
    else
        cat "$log"
        exit 1
    fi
}
run_check "$java_cmd" -Djava.awt.headless=true -jar "$project_dir/digital-ecosystem.jar" --self-test
run_check "$java_cmd" -Djava.awt.headless=true -cp "$classpath" PatternDemo
run_check "$java_cmd" -Djava.awt.headless=true -cp "$classpath" ecosystem.HuntAnimationSmoke
run_check "$java_cmd" -Djava.awt.headless=true -cp "$classpath" CliSmoke "$project_dir/digital-ecosystem.jar"
if "$desktop"; then run_check "$java_cmd" -cp "$classpath" GameUiSmoke; fi
cat "$log"
printf '%s\n' 'Verification passed. Results: tmp/verification/results.txt'
