#!/usr/bin/env bash
# Experimental: build atc as a GraalVM native image (see doc/development.md,
# "Native image"). The REPL defines the agent's classes at run time, which needs
# GraalVM's early runtime class loading (GraalVM CE/Oracle 25.3 or newer).
#
#   ./mill dist
#   GRAALVM_HOME=~/.graalvm/graalvm-community-25.3.4.1+1.1/Contents/Home native/build.sh
#   out/native/atc -Djava.home="$GRAALVM_HOME" -Datc.lib.classpath=out/dist.dest/atc-lib.jar ...
#
# The binary still needs a JDK 17+ at run time, named by -Djava.home: the compiler
# reads the JDK's class metadata from its jrt: file system, and runtime class
# loading falls back to it for JDK classes the image does not carry.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
: "${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM 25.3+ installation}"
DIST="$ROOT/out/dist.dest"
OUT="$ROOT/out/native"
mkdir -p "$OUT"
# -H:+RuntimeClassLoading   define classes at run time (REPL wrappers, StopRepl); they run interpreted
# -H:+AllowJRTFileSystem    the compiler's JDK classpath (jrt:/ under -Djava.home)
# -H:Preserve               keep every class, method and field agent code may link against: the
#                           agent-facing library jar (atc.lib + the Scala stdlib) and java.base;
#                           without it runtime-loaded code dies on the first member the app
#                           itself never used ("Unable to call AOT method")
# native/metadata           reachability metadata from the tracing agent (reflection, resources)
exec "$GRAALVM_HOME/bin/native-image" \
  -J-Xmx16g \
  -H:+UnlockExperimentalVMOptions \
  -H:+RuntimeClassLoading \
  -H:+AllowJRTFileSystem \
  -H:ConfigurationFileDirectories="$ROOT/native/metadata" \
  --enable-native-access=ALL-UNNAMED \
  -H:+ReportExceptionStackTraces \
  "$@" \
  -cp "$DIST/atc-lib.jar" \
  -H:Preserve=path="$DIST/atc-lib.jar",module=java.base \
  -jar "$DIST/atc.jar" \
  -o "$OUT/atc"
