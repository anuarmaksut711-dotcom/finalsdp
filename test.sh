#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sh "$project_dir/build.sh"
. "$project_dir/find-java.sh"
exec "$java_cmd" -Djava.awt.headless=true -jar "$project_dir/digital-ecosystem.jar" --self-test
