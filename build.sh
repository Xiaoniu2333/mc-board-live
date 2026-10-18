#!/usr/bin/env bash
# 构建 BoardLive.jar(需要 JDK 17+)
set -euo pipefail
cd "$(dirname "$0")"

JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"

major="$("$JAVAC" -version 2>&1 | sed -E 's/.*javac ([0-9]+).*/\1/')"
if [ -z "$major" ] || [ "$major" -lt 17 ]; then
  echo "需要 JDK 17 或更高版本(当前: $("$JAVAC" -version 2>&1))" >&2
  exit 1
fi
echo "JDK      : $("$JAVAC" -version 2>&1)"

rm -rf build/classes dist/BoardLive.jar
mkdir -p build/classes dist

mapfile -t SRC < <(find src/main/java -name '*.java')
echo "源文件   : ${#SRC[@]} 个"

"$JAVAC" -encoding UTF-8 --release 17 -d build/classes "${SRC[@]}"
"$JAR" cfe dist/BoardLive.jar mcboard.Main -C build/classes .

for f in config.yml Board.xaml; do [ -f "dist/$f" ] || cp -f "$f" dist/; done
cp -f README.md dist/ 2>/dev/null || true

echo
echo "构建完成: $(pwd)/dist/BoardLive.jar"
echo "配套文件: dist/config.yml, dist/Board.xaml"
echo
java -jar dist/BoardLive.jar -v
