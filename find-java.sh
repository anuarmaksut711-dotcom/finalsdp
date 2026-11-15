# Sourced by the launch scripts.
if [ -n "${JAVA_HOME:-}" ]; then
    java_cmd="$JAVA_HOME/bin/java"
    javac_cmd="$JAVA_HOME/bin/javac"
    jar_cmd="$JAVA_HOME/bin/jar"
else
    jdk_bin=$(dirname -- "$(command -v javac 2>/dev/null || printf '%s' /missing/javac)")
    java_cmd="$jdk_bin/java"
    javac_cmd="$jdk_bin/javac"
    jar_cmd="$jdk_bin/jar"
fi

for jdk_cmd in "$java_cmd" "$javac_cmd" "$jar_cmd"; do
    if ! command -v "$jdk_cmd" >/dev/null 2>&1; then
        printf '%s\n' 'JDK 17+ is required. Set JAVA_HOME or put the JDK bin directory on PATH.' >&2
        exit 1
    fi
done

javac_version=$("$javac_cmd" --version)
javac_major=${javac_version#javac }
javac_major=${javac_major%%.*}
javac_major=${javac_major%%-*}
case "$javac_major" in
    ''|*[!0-9]*) printf '%s\n' 'Cannot determine the JDK version.' >&2; exit 1 ;;
esac
if [ "$javac_major" -lt 17 ]; then
    printf '%s\n' 'JDK 17+ is required. Select a newer JDK with JAVA_HOME.' >&2
    exit 1
fi
