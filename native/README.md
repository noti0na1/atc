# atc as a GraalVM native image (experimental)

`build.sh` turns the `dist` jars into a single executable, `out/native/atc`, and
`start.sh` runs it. The sandbox still works: the REPL keeps compiling the agent's
Scala and defining its classes at run time through GraalVM's early *runtime class
loading*. Those classes run in an interpreter; the compiler, the REPL and the rest
of the application are compiled ahead of time.

Experimental: released as `atc-native-<os>-<arch>` archives beside the jars, installed
by the `atcn` wrapper, not part of `dist` or the tested build. `doc/development.md`
("Native image") has the design notes and measurements; this file is the how-to.

## Install a release

```bash
curl -fsSL https://raw.githubusercontent.com/noti0na1/atc/refs/heads/main/atcn -o atcn
chmod +x atcn && ./atcn setup      # ~/.local/bin/atcn (+ atc, whose code it shares); downloads the binary
atcn -C ~/some/project             # same commands as atc: run, update, self update, self uninstall, dev
```

`atcn` (repo root) is `atc` with the assets swapped: it downloads `atc-lib.jar` and
`atc-native-<os>-<arch>.tar.gz` from the latest release, verifies GitHub's digests,
extracts the binary to `~/.atc/native/` and runs it with the JDK it finds
(`ATC_JAVA_HOME`, `JAVA_HOME`, then the `java` on `PATH`). Linux and macOS, x64 and
arm64. Windows users download `atc-native-windows-x64.zip` and run `atc.exe` by hand with
`-Djava.home=<jdk> -Datc.lib.classpath=<path to atc-lib.jar>`.

CI (`native` in `.github/workflows/scala.yml`) builds one binary per target from the
jars the `build` job produced, on every push and pull request as well as on a release,
run-tests each one against the mock LLM server (`tests/run_test.sh native`) and keeps it
as a workflow artifact; publishing a release only downloads the artifacts and attaches
them beside the jars.

## CI runners

The build wants about 14 GB of builder heap and takes 7 minutes on a 32 GB machine. On
GitHub's standard runners (16 GB, 4 cores; 7 GB on macOS Apple Silicon) only Linux x64
fits, taking 19 minutes with 40% of the time in GC. Linux arm64 ran out of memory after
110 minutes even throttled to 2 builder threads, Windows fits one run in three, and
macOS arm64 cannot be built at all; those three targets are `continue-on-error` until a
variable names a bigger machine, so the rest of CI stays meaningful.

The fix is a bigger machine per target, chosen with a repository variable (Settings >
Secrets and variables > Actions > Variables) holding a runner label; the workflow falls
back to the standard runner when the variable is unset, and `build.sh` sizes the builder
heap from the machine's memory (all but 3 GB, at most 24 GB) and uses every core:

| Variable | Target | Standard runner |
|---|---|---|
| `ATC_RUNNER_LINUX_X64` | linux-x64 | `ubuntu-latest` |
| `ATC_RUNNER_LINUX_ARM64` | linux-arm64 | `ubuntu-24.04-arm` |
| `ATC_RUNNER_MACOS_ARM64` | macos-arm64 | `macos-latest` (7 GB, cannot build) |
| `ATC_RUNNER_WINDOWS_X64` | windows-x64 | `windows-latest` |

Three kinds of label work:

- **A self-hosted runner** (free; any plan, personal repositories included). Register a
  machine with 32 GB or more under the repository (Settings > Actions > Runners > New
  self-hosted runner), give it a label such as `atc-native-macos-arm64`, and set the
  variable to that label. A developer's own Mac covers macOS arm64 in about 7 minutes; a
  Linux box or VM covers the Linux targets. Self-hosted runners on a public repository
  run whatever a pull request submits, so keep GitHub's default of requiring approval
  for workflows from first-time contributors, or restrict the `native` job to pushes
  and releases (`if: github.event_name != 'pull_request'`) when opening the repository
  to outside pull requests.
- **GitHub larger runners** (organizations on the Team or Enterprise plan; billed per
  minute even for public repositories). Move the repository into an organization on
  the Team plan, create runners in the organization's runner groups (for example an
  8-core, 32 GB Linux x64 and arm64 runner, and a macOS xlarge for arm64), and set the
  variables to their labels. A 7-minute build on an 8-core Linux runner costs a few cents.
- **A third-party runner service** (Blacksmith, Namespace, Depot, BuildJet, RunsOn and
  the like): install the service's GitHub App and set the variables to the labels it
  documents. Check that it offers the architecture you need; most have Linux x64 and
  arm64, fewer have macOS.

When a variable is set, the thread cap for that target is dropped and `experimental`
is lifted for macOS arm64, so a failing build there fails the run as it should.

## Build and run from a checkout

```bash
./mill dist                                                  # the jars the image is built from
GRAALVM_HOME=/path/to/graalvm-25.3 native/build.sh
native/start.sh -C ~/some/project                            # any atc flag passes through
native/start.sh -c cfg.json -p 'run: 1 + 1'                  # one non-interactive turn
atcn dev .                                                   # or install it as the atcn binary
```

Needs GraalVM 25.3 or newer (`native-image` with `-H:+RuntimeClassLoading`), about
8 minutes on a 32 GB machine and a builder heap of `ATC_NATIVE_XMX` (default: the
machine's memory minus 3 GB, at most 24 GB; 13 GB, the most a 16 GB runner has, is the
edge and takes about 20 minutes). The result is about 600 MB.
`ATC_NATIVE_THREADS` caps the builder threads (fewer threads need less heap),
`ATC_NATIVE_MARCH` (default `compatibility`) sets the x64 target ISA; extra
`native-image` arguments are passed through.

`start.sh` loads `.env` like the top-level `start.sh`, then finds a JDK for the
binary: `ATC_JAVA_HOME`, `JAVA_HOME`, `/usr/libexec/java_home`, then the `java` on
`PATH`. Any JDK 17+ works; a GraalVM is only needed to build. The binary is **not
self-contained**: the compiler reads the JDK's class metadata from that JDK's
`lib/modules`, and runtime class loading falls back to it for JDK classes the image
does not carry. `ATC_NATIVE_OPTS` adds runtime options (`-Xmx...`, `-D...`).

Every run prints JDK 25's `sun.misc.Unsafe` deprecation warning for Scala's
`LazyVals` on stderr; nothing silences it in a native binary.

## What is in this directory

| File | Role |
|---|---|
| `build.sh` | the `native-image` invocation and the reasons for each flag |
| `start.sh` | checkout launcher: `.env`, JDK detection, `-Djava.home`, the library classpath |
| `../atcn` | release installer/launcher, sources `../atc` (tests: `../tests/atcn_test.sh`) |
| `metadata/reachability-metadata.json` | hand-curated metadata: the tracing agent's output for echo-model runs and real turns over the OpenAI Responses and chat-completions adapters (Jackson internals, kotlin-reflect, TLS providers), REPL wrapper classes removed |
| `sdk-reflection.py` | generates `out/native/metadata/reachability-metadata.json` at build time: reflection metadata for every provider-SDK model class in the jar |

## Why the image needs metadata at all

Native image is closed-world: whatever the analysis cannot prove reachable is left
out, and reflection, resources, JNI and proxies only work for elements listed in
*reachability metadata*. atc has three kinds of dynamic access:

1. **Agent code.** Compiled at run time, it can link against any member of the
   agent-facing library, the Scala standard library and the JDK. `-H:Preserve`
   keeps all of `atc-lib.jar` and the JDK packages agent code can reach
   (`java.lang`, `java.util`, `java.time`, `java.text`, `java.math`; the validator
   blocks `java.io`/`nio`/`net` and reflection) in the image, so no metadata is
   needed for it. Without it the first member the app itself never used fails with
   `Unable to call AOT method`. Preserving all of `java.base` works too but needs a
   20 GB builder heap.
2. **The provider SDKs.** They (de)serialize requests and responses with Jackson
   over reflection: constructors, `@JsonProperty` getters, `@JsonAnySetter`. Which
   classes are touched depends on what the model sends back, so a trace of one run
   never covers them all. `sdk-reflection.py` reads the class files of the packages
   the adapters use (`com.openai.models.{responses,chat,completions}`,
   `com.anthropic.models.messages`, the shared types beside them and both `core`
   packages; the SDKs' other services are several times larger and never reached)
   and registers, per class, its constructors (concrete classes only) and every
   Jackson-annotated method and field: the exact shape the trace shows Jackson
   invoking.
3. **Everything else.** The compiler, JLine, okhttp, Jackson's own internals,
   kotlin-reflect, the TLS providers. This is what the tracing agent records and
   what `metadata/reachability-metadata.json` holds.

## Adding a library, or a new use of reflection

There is no static check; metadata is proven by running the code path. The
procedure, in order of preference for each kind of access:

1. **Take what the library ships.** Many jars carry `META-INF/native-image/…` (JLine
   does) and `native-image` reads it automatically. The
   [GraalVM reachability-metadata repository](https://github.com/oracle/graalvm-reachability-metadata)
   has entries for okhttp, Jackson, the Jackson Kotlin module, Kotlin and
   scala-lang; copy the directory for the pinned version into `metadata/`.
2. **Generate what has a known shape.** If the library is data-driven the way
   Jackson is, extend `sdk-reflection.py` rather than tracing: annotations in the
   class files say which members the framework will invoke.
3. **Trace the rest** with the same workload on the GraalVM JVM, then merge:

   ```bash
   G=$GRAALVM_HOME/bin/java; D=out/dist.dest
   $G -agentlib:native-image-agent=config-merge-dir=native/metadata \
      -Datc.lib.classpath=$D/atc-lib.jar -jar $D/atc.jar -m <model> -p '...'
   ```

   Remove the `rs$line$N` entries (REPL wrapper classes) it records; they do not
   exist at build time. Trace a fixed workload (the echo smoke run, one turn per
   provider adapter, a permission prompt), not an ad-hoc session, so a re-trace is
   reproducible.
4. **Hand-write** only what no workload reaches, with a comment saying why.

Then rebuild and run the workload on the binary with `ATC_DEBUG=1`. A missing
registration fails with `MissingReflectionRegistrationError` (or the resource and
JNI equivalents) and the message contains the JSON snippet to add.

Two rules, each backed by a failed build:

- Do not register wholesale to be safe. `-H:Preserve=path=atc.jar` crashes the
  builder (it cannot make constructors of abstract classes reflective), and
  registering every method of every SDK class makes 870k methods reachable and the
  builder runs out of memory. Register the shape you can justify.
- Keep generated metadata out of git. `sdk-reflection.py` writes to `out/native`
  so a new SDK version regenerates it; only the curated trace is checked in.

## Estimating the need from bytecode

`native-image` does part of this itself: reflective calls with constant arguments
(`Class.forName("x")`, `getMethod("m")`) are folded during analysis and need no
metadata. For the rest there are two built-in tools, both experimental in 25.3:

- `-H:TrackDynamicAccess=all` (build time; pass it to `build.sh`) writes
  `out/native/dynamic-access/<jar>/{reflection,resource}-calls.json`: a map from
  each reflective API to the call sites using it in *reachable* code. For atc.jar
  that is 24 reflection APIs at 314 sites (Jackson 87, kotlin-reflect 82, the
  compiler 51, JLine 21, okhttp 17) and 6 resource APIs at 22 sites; for
  atc-lib.jar 15 APIs at 83 sites, all in the Scala stdlib. It says where metadata
  may be needed, not what: a call whose argument comes from data (a JSON type
  name, a config string) can only be resolved by running it. Read it when adding a
  library: a jar with no entries needs nothing.
- `-H:+MetadataTracingSupport` (build time) plus `-XX:TraceMetadata=path=<dir>`
  (run time) make the binary itself write the metadata a run used, like the JVM
  agent but for the real image, including interpreted agent code: an echo run of a
  `java.time` snippet records `java.time.LocalDate` among 51 entries. It emits only
  what `-H:Preserve` kept or what is missing, so GraalVM's intended workflow is:
  build a preserving, tracing image, run the workloads, then build the lean image
  from the trace. Both options cost build memory (about 2 GB more), which is why
  `build.sh` does not enable them by default.

`sdk-reflection.py` is the third kind of estimate: annotation-driven inference for
one framework. Extend it before reaching for a general bytecode scanner; a general
scanner finds the call sites `TrackDynamicAccess` already lists and still cannot
name the data-driven targets.

## Known limits

- Agent code is interpreted: a tight loop runs about 20x slower than on the JVM.
  File, command and network work is unaffected.
- The Anthropic adapter is covered by the generator but has not been exercised
  against the API; expect its first run to surface a Jackson internal the way the
  OpenAI adapter's first run did.
- Agent code reaching a JDK package outside the preserved list (`java.security`,
  `javax.crypto`, ...) fails with `Unable to call AOT method`; add the package to
  `-H:Preserve` in `build.sh`. The image is about 600 MB.
