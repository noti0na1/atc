# ATC development guide

This guide covers the build, architecture, implementation constraints and tests. The
[README](../README.md) covers installation, configuration and the agent-facing API.

The [type-system background](#type-system-background) explains the theoretical model;
the [capability design](#the-capability-design) maps it to ATC. The remaining sections
describe runtime enforcement, execution, configuration and maintenance.

## Building and running

Use JDK 17 or newer and the included Mill launcher. `build.mill` pins Mill, the Scala
nightly compiler and dependencies. Capture checking and safe mode require that compiler;
compiler upgrades must pass the capability and sandbox suites.

```bash
./mill app.compile
./mill app.test
./mill app.test.testOnly atc.ReplSessionSuite
./mill app.test.testOnly atc.ReplSessionSuite -- '*timeout*'
./mill __.checkFormat
./mill __.reformat
./mill dist
bash tests/atc_test.sh
```

On Windows, replace `./mill` with `.\mill.bat`. Tests run serially there because Mill's
parallel queue can duplicate compiler-intensive suites. If the Mill server cannot start,
use `./mill --no-server ...`. The permission suite starts a local HTTP server and needs
permission to bind a loopback socket.

`dist` writes `atc`, `atc.ps1`, `atc.cmd`, `atc.jar`, `atc-lib.jar` and `version.txt` to
`out/dist.dest/`. The Unix launcher and JARs form the Unix distribution; Windows uses the
PowerShell launcher with the same JARs. The batch launcher is a compatibility entry point.

`./start.sh` and `.\start.ps1` load `.env`, rebuild stale distributions and launch ATC.
They preserve non-empty exported environment values and pass application arguments through.
The build runs in the checkout; ATC retains the launch directory unless `-C` overrides it.
Environment files contain literal `KEY=value` entries; shell expansion is not performed.

| Variable | Purpose |
|---|---|
| `ATC_MODEL`, `ATC_CONFIG`, `ATC_CWD` | Start-script defaults for `-m`, `-c`, `-C` |
| `ATC_ENV_FILE` | Alternative environment file |
| `ATC_SKIP_BUILD=1` | Skip the start script's rebuild check |
| `ATC_JAVA_OPTS` | Additional JVM flags for Unix launchers and checkout start scripts |
| `ATC_DEBUG` | Enable stack traces and stream/terminal diagnostics when set |
| `ATC_ASCII` | Select ASCII terminal glyphs when set |

For development without packaging, use `./mill -i app.run`. For manual REPL checks,
`./mill app.test.runMain atc.Scratch file.scala` evaluates snippets separated by `// ---`.
An `echo` provider supports local testing: `run: <Scala code>` invokes the REPL; other
requests are echoed. It needs no API key or network connection.

## Native image (experimental)

`native/build.sh` builds `out/native/atc` with GraalVM's `native-image` (GraalVM 25.3 or
newer, set `GRAALVM_HOME`; run `./mill dist` first), and `native/start.sh` runs it like
`start.sh` does the JVM build: it loads `.env`, finds a JDK (`ATC_JAVA_HOME`, `JAVA_HOME`,
`/usr/libexec/java_home`, then the `java` on `PATH`) for `-Djava.home`, sets the library
classpath and version, and passes every flag through (`ATC_NATIVE_OPTS` adds runtime
options). The REPL defines the agent's classes at run time, so the build enables GraalVM's
early *runtime class loading* (`-H:+RuntimeClassLoading`): classes defined at run time are
interpreted, never compiled, while the compiler, the REPL and the rest of the application
are ahead-of-time compiled. The pieces that make it work:

- `-H:Preserve=path=atc-lib.jar,package=java.lang.*,...` keeps every member of the
  agent-facing library, the Scala standard library and the JDK packages agent code can reach
  (`java.lang`, `java.util`, `java.time`, `java.text`, `java.math` and their subpackages; the
  validator blocks `java.io`/`nio`/`net` and reflection) in the image. Native image otherwise
  drops whatever the application never used, and runtime-loaded code dies on the first such
  member (`Unable to call AOT method ...`). All of `java.base` works too but needs a 20 GB
  builder heap; the package list fits GitHub's 16 GB runners (`ATC_NATIVE_XMX=12g`).
- `-H:+AllowJRTFileSystem` plus `-Djava.home=<jdk>` at run time: the compiler reads the JDK's
  class metadata from that JDK's `jrt:` file system, and runtime class loading falls back to
  it for JDK classes outside the image. Any JDK 17+ works (a GraalVM home is not required),
  so the binary is not self-contained.
- The REPL's interrupt instrumentation gives each REPL class loader its own copy of
  `dotty.tools.repl.StopRepl`, read as a `.class` resource, which an image never carries.
  The build copies its bytes to `atc/StopRepl.class.bin` and `Sandbox.SandboxLoader` serves
  them under the class-file name.
- The app assembly is a plain jar (`prependShellScript = ""`): the launcher-script prefix
  Mill adds by default hides every resource of the jar from `native-image`.
- `native/metadata/reachability-metadata.json` is the tracing agent's output
  (`-agentlib:native-image-agent=config-output-dir=...` on the GraalVM JVM) for echo-model
  runs and real turns over the OpenAI Responses and chat-completions adapters, with the
  REPL wrapper classes (`rs$line$N`)
  removed: Jackson's internals, kotlin-reflect, the TLS providers, JLine.
- The provider SDKs (de)serialize requests and responses with Jackson over reflection, and
  the trace only covers the response types a run happened to see. `native/sdk-reflection.py`
  reads the class files of the packages the adapters use (`com.openai.models.{responses,chat,
  completions}`, `com.anthropic.models.messages`, the shared types beside them, both `core`
  packages; the SDKs' other services are never reached) and registers, per class, the constructors (concrete classes only: registering an
  abstract class's constructor crashes the builder) and the Jackson-annotated methods and
  fields, which is exactly the shape the trace shows Jackson invoking (`<init>`, the
  `_field()` getters, `putAdditionalProperty`). Registering every method of every SDK class
  instead makes 870k methods reachable and the build runs out of memory; preserving the
  whole app jar crashes the builder.
- `-Ob` (quick build) and `ATC_NATIVE_XMX` of builder heap (20 GB on a developer machine,
  12 to 13 GB on CI's 16 GB runners, where the build is GC-bound and takes about 20
  minutes; `ATC_NATIVE_THREADS` caps the builder threads, fewer need less heap). An `-O2`
  image runs faster but builds much longer. `-march=compatibility` on x64 so one binary
  runs on every x64 machine. Do not drop the JNI metadata of preserved types
  (`-H:-PreserveIncludesJNI`) to save memory: the interpreter then cannot call
  caller-sensitive methods such as `MethodHandles.lookup`, which Scala's lazy vals need.

Two GraalVM tools estimate the metadata need without a JVM agent: `-H:TrackDynamicAccess=all`
at build time writes `out/native/dynamic-access/<jar>/{reflection,resource}-calls.json`, a map
from reflective API to the call sites using it in reachable code (for atc.jar: 24 APIs, 314
call sites, most in Jackson, kotlin-reflect and the compiler), and `-H:+MetadataTracingSupport`
at build time plus `-XX:TraceMetadata=path=<dir>` at run time makes the binary itself write
the metadata a run used, including from interpreted agent code (a `java.time` snippet
records `java.time.LocalDate`). Both are experimental in 25.3 and cost build memory, so
`native/build.sh` takes them as extra arguments rather than enabling them by default.

**Publishing.** `atcn` (repo root) installs and runs the native image the way `atc` does
the jars: it sources `atc` and overrides the asset list, the install step, the digest
bookkeeping, `dev` and `run` (tests: `tests/atcn_test.sh`). The release workflow's
`publish-native` job builds `atc-native-<os>-<arch>.tar.gz` (Windows: `.zip`) on one
runner per target and uploads it beside the jars; targets no standard runner can build
(macOS arm64 at 7 GB, the untested Windows ones) are `continue-on-error`.

Verified on macOS arm64: the echo-model smoke run, a Responses API model (streaming,
reasoning, tool calls) and a chat-completions model editing a small project in `-p` runs,
and an interactive session through a pty with a permission pop-up, a second turn,
next-input prediction, `/cost` and `/quit`. The binary starts, compiles and runs a
one-line snippet in about 0.4 s wall (2.5 s and 8 s of CPU on the JVM) at about 220 MB peak
RSS (390 MB); a tight 20-million-iteration loop in agent code takes 2 s interpreted against
0.09 s JIT-compiled. Execution timeouts and interrupts work (the `StopRepl` flag is honoured
by the interpreter). The image is about 600 MB and a quick build takes 8 minutes at 20 GB
(430k reachable methods: the app's 190k, the preserved Scala library and JDK packages, and
the SDK model classes' constructors and Jackson members). Every run
prints JDK 25's `sun.misc.Unsafe` deprecation warning for `scala.runtime.LazyVals` on
stderr. Not part of `dist` or the tested build; released and installed as described under "Publishing" above.

## Architecture

| Component | Responsibility |
|---|---|
| `lib` | Agent-facing capability types, data types and `Interface`; compiled with capture checking |
| `app` | Configuration, models, permissions, host operations, REPL and terminal |
| `agent/` | Turn loop, completion decisions, history, context estimates, prompts and input prediction |
| `config/` | Configuration layers, validation, key bindings and model catalog |
| `host/` | File, process, network and user operations implementing `Interface` |
| `llm/` | Provider-neutral messages and provider adapters |
| `perms/` | Policy, permission scopes, path patterns, modes and gitignore visibility |
| `platform/` | OS behavior, path normalization and portable path globs |
| `sandbox/` | Compiler setup, REPL evaluation, validation, class loading and interruption |
| `ui/` | JLine input, streaming output, Markdown and syntax highlighting |

One turn follows this path:

```text
App → Agent → ChatModel.complete → CompletionPolicy
                  ↓ tool calls
          ScalaToolRunner → ReplSession → Host
                  ↑ execution result and permission decisions
```

`Agent` controls turn state. `Conversation` owns history and protocol repair;
`ContextManager` owns token estimates and history fitting. `ScalaToolRunner` decodes
`run_scala`, invokes the REPL, records execution time and renders results through
`ToolOutput`. `AgentMessages` contains notices exchanged with the model and UI.

`Host` implements `Interface` directly through file, process, network and interaction
traits. `HostOutput`, `HostLlm` and `HostUi` are dependencies supplied by `App` or tests.
The REPL shares library classes with the application, so calls need no serialization layer.

## Type-system background

### Authority, effects and retained capabilities

A capability is a reference that authorizes an operation. Passing `FileSystem` to a
method provides file access through that object. A `using` parameter makes the dependency
explicit in the method signature, but implicit argument passing alone does not prevent a
closure or object from retaining the reference. Capture checking tracks that retention.

In ATC, capability types and runtime permissions answer different questions. The type
system determines whether code can read, write, execute or communicate at all. The policy
determines which concrete files, commands and hosts an available capability may access.
For example, a write can compile in full mode and still fail because its path is locked.
A write in read-only mode fails before the policy receives an operation.

### Capture sets and subcapturing

`T^{c}` describes a value that may retain capability `c`; `T^{c, d}` permits both.
Subcapturing expresses coverage: `{c}` is covered by `{c, d}`. Coverage can also follow a
reference's declared captures, so a handle typed `FileEntry^{fs}` is accounted for by
`fs`. For ordinary capturing types, a smaller permitted capture set gives a more specific
type. Capture sets describe possible dependencies, not a list of operations already run.

Function types make the distinction explicit:

| Type | Capabilities retained by the function |
|---|---|
| `A -> B` | None |
| `A ->{c} B` | Those covered by `c` |
| `A => B` | A general capturing function, written `A ->{any} B` |

See the compiler's [capture-checking basics](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/basics.html)
for the subcapturing rules and function notation.

This helper expresses an ATC callback's dependency directly:

```scala
def printer(using user: UserIO^): String ->{user} Unit =
  (text: String) => println(text)
```

The callback retains the supplied `user`. Its return type is `Unit`, but that does not
make it pure: purity depends on captured capabilities. Converting an effectful method to a
function must preserve this dependency; the default-argument regression discussed below
is important for precisely this reason.

### Scope and capture polymorphism

`T^` abbreviates `T^{any}`, but `any` is scoped. It is not one unrestricted global
permission that can absorb capabilities introduced in deeper scopes. Scoped capability
parameters and restrictions on result captures prevent a callback from returning a
resource whose lifetime ends when the callback returns. This includes returning a
closure, collection or object that indirectly retains the resource. The upstream
[scoped-capability rules](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/scoped-capabilities.html)
describe the role of lexical scope.

ATC's `requestFiles` also preserves the caller's access mode with a capture-set parameter.
Its full overload is declared in `Interface` as:

```scala
def requestFiles[T, C^](path: String, access: Access, reason: String)
                      (using UserIO^, FileSystem^{C})
                      (op: (FileSystem^{any.rd, C}) ?=> T): T
```

`C^` abstracts over a capture set. The contextual callback receives an inner file-system
given that includes the caller's captures. It therefore retains a full view when the caller
has one and remains read-only for a read-only caller. The scoped component prevents the
temporary capability from escaping in `T`. This is ATC's use of
[explicit capture polymorphism](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/polymorphism.html).

Within an initialized ATC REPL, the following returns ordinary text successfully:

```scala
val copiedText = requestFiles(".", Access.Read, "inspect project") {
  read("notes.txt")
}
```

Returning the temporary file system instead is rejected:

```scala
val escaped = requestFiles(".", Access.Read, "inspect project") {
  summon[FileSystem^]
}
```

The first result does not retain the temporary authority. It may still contain confidential
information, so lifetime checking alone is insufficient for data protection; `Classified`
provides that separate constraint.

### Read-only views and stateful capabilities

ATC's `Cap` extends `Stateful` and `ExclusiveCapability`. For that combination, a bare
capability type receives a read-only capture set. `x.rd` restricts access through `x`;
an `update` method requires full access. This is an access restriction on the same runtime
object, not a copy of the object or proof that nobody else can mutate it.

The ordinary rule that smaller capture sets yield subtypes needs this qualification:
stateful read-only sets also express access permissions. A full reference can be used
through a read-only view without erasing its tracked dependency. The compiler represents
this distinction internally with a reader qualifier. See
[stateful capabilities](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/mutability.html).

```scala
val ro: FileSystem^{fs.rd} = fs
ro.access("notes.txt").read()           // permitted when the file policy allows it
ro.access("notes.txt").write("changed") // compile error: read-only receiver
```

`Exec` and `Network` extend only `ExclusiveCapability`. They do not support `.rd`; a bare
`Exec` is already a full capability. Do not generalize the bare-`FileSystem` convention to
every capability type. `CapabilitySuite` tests this distinction.

### Safe mode and the trusted implementation

Capture tracking requires APIs to expose their real dependencies. An apparently pure
method that secretly reads a global file handle would invalidate that reasoning. Safe mode
restricts untracked casts, reflection, unsafe annotations and access to global components.
`@assumeSafe` admits a trusted library definition; it is a responsibility of the library
author, not a proof generated for the implementation. `@rejectSafe` excludes selected
definitions from safe code. See the compiler's
[safe-mode reference](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/safe.html).

ATC compiles `lib` with capture checking, explicit nulls and safe initialization checks.
The host implementation in `app` uses ordinary Scala signatures and Java I/O. Consequently,
the implementation of each admitted API must preserve the interface contract manually:
use the supplied capability, check the appropriate scope and policy, and keep classified
values out of ordinary outputs and exceptions.

The online language documentation follows newer nightlies. The executable examples above
were checked against the compiler pinned in `build.mill`; use ATC's compiler suites to
resolve differences during upgrades. Theoretical properties of capture checking do not
constitute a formal verification of ATC, its dependencies or its runtime environment.

## The capability design

The build bundles `lib/src/atc/lib/Interface.scala` into the system prompt and `/interface`.
Its comments are API documentation for the model: keep contracts and examples there;
keep implementation rationale in this guide or beside host code. Add operations to the
interface and host together, then verify their required capabilities.

Capture sets describe the capabilities a value may retain. For ATC's stateful capability
types, the bare type is the read-only view; `^` denotes full access. For example,
`FileSystem` can read and `FileSystem^` can read or write. `FileEntry` mutation methods are
`update def`s, which require full access. A full file system can be explicitly restricted:

```scala
val ro: FileSystem^{fs.rd} = fs
```

| Mode | Machine capabilities supplied by the preamble |
|---|---|
| `readonly` | `io: IOCap`, `fs: FileSystem^{io.rd}` |
| `local` | `io: IOCap^`, `fs: FileSystem^{io}`, `ex: Exec^{io}` |
| `full` | Local capabilities plus `net: Network^{io}` |

Every mode also supplies `user: UserIO^` for output, questions, TODO updates and normal
`chat` calls. `UserIO` is independent of the machine root so user interaction remains
available in read-only mode. `Exec` and `Network` have no read-only view. Command operations
require both `Exec^` and `FileSystem^`, including when redirection writes a file.

Capability constructors are private to ATC. `Runtime` and `Derivations` provide the
sandbox's internal bootstrap API and are marked `@rejectSafe`. Agent code cannot derive
missing capabilities from a root it holds.

The preamble loads each given in a separate REPL round. Each therefore occupies a separate
wrapper class: a read-only operation capturing `fs` does not also capture the full `user`
capability. Keep the givens at the top level; grouping them in an object changes capture
checking behavior.

### Classified data

`Classified.map` and `flatMap` accept callbacks of type `T ->{any.rd} B`. They may capture
read-only capabilities. Printing, writing, commands, network requests, permission requests
and normal `chat` all require full capabilities and are rejected in those callbacks.

`ClassifiedImpl` stores a `Try`: non-fatal computation failures remain confidential.
Authorized destinations are the user terminal, classified files, the configured classified
model and permitted HTTP hosts. HTTP calls with classified headers or bodies return
classified responses, preventing a server from reflecting a secret into ordinary output.
Validate public parameters and permissions before inspecting classified values, and keep
subsequent failures inside the classified result or user-only output.

The data-flow argument relies on both parts of the API. `map` does not expose a plain
result, and its callback cannot capture a full output capability. Thus deriving a Boolean
from a secret keeps the Boolean classified too. Allowing `println`, a mutable file handle,
or an untrusted model callback inside `map` would expose information even if the callback
returned a harmless value.

An explicit read-only file system can be used to compute a classified result:

```scala
classify("notes.txt").map(path => read(path)(using ro))
```

The `printer` callback defined above is rejected in the same position:

```scala
classify("private").map(printer) // requires UserIO^, which map cannot capture
```

Exception handling is part of this boundary. A callback may throw an exception containing
a secret, so a failed `Try` must remain classified. Authorized output methods must also
handle failures in user-defined rendering, including `toString` and `getMessage`, without
letting the exception reach the model. Fatal errors must abort evaluation rather than
becoming a condition the agent can catch and inspect.

`classifiedChat(String)` is treated as pure. This assumes the configured endpoint is
isolated and has no observable effects beyond its result; ATC cannot establish that about
an arbitrary endpoint. Timing, termination and resource-consumption side channels are
outside the classified-data guarantees.

**Use overloads instead of default arguments on capability-taking API methods.** Default
arguments have allowed method wrappers to lose required captures when passed to
`Classified.map`. `CapabilitySuite` covers this regression. Changes to callback signatures,
capability requirements or preamble structure must retain those tests.

### References

The pinned compiler implements experimental
[capture checking](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/index.html),
[safe mode](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/safe.html)
and [mutable capabilities](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/mutability.html).
ATC's design is derived from [TACIT](https://github.com/lampepfl/tacit).

## Defence in depth

Safe mode and capture checking enforce the static capability boundary. `CodeValidator`
provides early diagnostics for common restricted APIs and unsupported catches. It is a
lexical check, with possible false positives and false negatives. Keep it small; accepting
code here does not establish that the code is safe. Disabling safe mode removes the
compiler's API restrictions.

`SandboxLoader` shares `scala.*` and `atc.lib.*` with the application, excluding compiler
implementation packages. Other classes resolve through the JDK platform loader. Class
resources are hidden so REPL instrumentation does not redefine the shared API classes.

The host checks permissions at each operation. Scope checks apply even when a locked rule
already determines access. Command and host deny rules remain effective inside temporary
scopes and after session grants. External commands run with the user's OS privileges;
ATC's file and HTTP permissions do not constrain the internals of those commands.

## The sandbox

`App.ensureSession` initializes the REPL on the first Scala tool call or `/run`.
Text-only turns do not start a compiler. Reset and mode changes discard the previous
session; initialization remains deferred. `Agent.turn` and `ScalaToolRunner` accept the
session lazily, and cancellation is checked after initialization before executing code.

`ReplSession` persists definitions until a reset, new session or mode change. Safe mode is
imported after the trusted preamble. Compiler diagnostics and runtime results are returned
as `ExecutionResult`; an exception recorded by `CappedRendering` marks evaluation failure.
A string containing the word `error` in ordinary output does not determine run success.

With an execution timeout configured, evaluation runs on a daemon worker. `ExecutionClock`
excludes nested waits for user input, external commands and model calls that have their own
timeouts. Interrupts set the compiler's REPL stop flag and interrupt blocking operations.
`skipInvalidWrapper` advances past a potentially incomplete wrapper class. Completed file,
process or network effects are not rolled back after interruption or timeout.

Evaluation temporarily replaces `System.out` and `System.err`, so a process-wide lock
serializes capture. The same lock protects selection of the session's host in `Runtime`,
including lazy preamble initialization. Lock acquisition has a timeout: an evaluation that
cannot stop must not block every subsequent request indefinitely. Such a stuck evaluation
requires restarting ATC.

After interruption or timeout, the stopped wrapper is excluded from future imports while
earlier definitions remain available. The driver may already have advanced `objectIndex`;
advancing it again would create a valid index with no registered wrapper and make later
definitions fail with `key not found`. The recovery path compares the pre-evaluation and
returned indexes and invalidates only the stopped wrapper. Run `ReplInterruptionSuite`
separately as well as in the full suite: unrelated compiler sessions can populate Scala's
shared wrapper-name table and hide this failure.

Output capture retains at most 4 MiB and reports truncation. Top-level value echoes have a
separate character limit. User-visible prints also enter the REPL capture; the TUI records
a bounded prefix to subtract already-displayed output from result panels. Preserve leading
whitespace in capture because subtraction uses exact text.

| Command | State reset |
|---|---|
| `/clear` | Conversation, queued notes and usage accounting |
| `/reset` | REPL and spawned processes; conversation and session grants remain |
| `/mode` | REPL and spawned processes, with the selected capabilities |
| `/new` | REPL, conversation, task notes, TODOs, retained output, usage and session grants |

REPL restarts queue a notice for the model's next turn. `/run` queues the user's code and
its result because definitions are shared with the agent. Closed sessions reject further
runs. Input predictions are invalidated after state changes and before shutdown.

## The permission model

`Policy` combines configured file rules, session grants and temporary permission scopes.
For a canonical path, matching granting rules supply access and every matching rule imposes
an access ceiling. The strictest ceiling wins; unmatched paths have no access.
Classification and locking apply if any matching rule enables them. Temporary and session
grants may widen access but cannot remove classification or exceed a locked rule.

### Policy algebra

Access levels form the finite order `None < Read < Write`. For canonical path `p`, let
`M(p)` be all matching rules and `G(p)` the subset allowed to grant at `p`. A global or
explicit rule can grant anywhere it matches; a project rule can grant only inside its
project. The implementation in `configPerm` is equivalent to:

```text
grant(p)   = maximum explicit access in G(p), default None
ceiling(p) = minimum explicit access in M(p), default Write
config(p)  = min(grant(p), ceiling(p))

scoped(s,p) = maximum matching file grant in s and its ancestors, default None
access(s,p) = config(p)                         when p is locked
              max(config(p), scoped(s,p))       otherwise
effective(s,p) = min(access(s,p), Read)          in read-only mode
                 access(s,p)                    in other modes
```

A missing rule access field imposes no access ceiling; it may independently set
classification or locking. For example, a project-wide write rule plus a read rule on
`src` yields read access in `src`. An additional classification rule on `src/private`
changes how content may be observed without changing that read level. A temporary write
grant may raise the read level only when no matching rule locks it.

`matchingRules` caches immutable matches, including inherited ancestor matches, in a
bounded LRU map. It does not cache temporary grants or mode-dependent decisions, so opening
or closing a scope cannot leave stale effective permissions. The scope must be resolved
even for locked paths; otherwise a closed capability could remain usable under a locked
read rule.

### Scope lifecycle

A `request*` call opens a child scope, and its capability carries that scope's ID. Closing
the block closes the scope and kills processes owned by it. Process handles require an
open originating scope; `runningProcesses` filters by scope visibility.

At runtime, a request resolves its parent scope, checks mode and deny restrictions, and
asks only for permissions not already held. `AllowOnce` applies to the child scope;
`AllowSession` also records the grant on `ScopeId.Base`. The callback runs through
`Host.inScope`, whose `finally` block terminates scoped processes and closes the scope.
Denial throws before a child scope is opened. The compiler's lifetime checks and the
host's scope IDs therefore enforce complementary parts of the same request contract.

Permission requests display numbered command or host rows. The host sorts and deduplicates
requested patterns so the displayed order agrees with decision notes. `Policy.sessionGrants`
provides the current grant list; `/perms revoke` selects from it and `Policy.revoke` removes
the selected grant from future checks. Configuration rules and open-scope semantics remain
separate. Revoking a command grant does not terminate an existing process; `/kill` does that.
The agent receives a queued notice after a user revokes a grant.

`Decision.Revise(instructions)` records user feedback without granting access or opening a
scope. The pending request throws a permission error. `ToolOutput` appends the complete,
JSON-quoted instructions after the bounded execution output. A revision is distinct from
`Deny`: the model should change its plan and may submit a narrower permission request.
Neither decision adds a permanent deny rule; configured deny rules remain unchanged.

File patterns match a path or an ancestor. Component globs match at any depth; relative
patterns with separators use the layer's base; absolute patterns and `~` use an absolute
path. `PathGlob` handles slash-based globs on all platforms. Canonicalization resolves
symlinks, including dangling write targets. Directory traversal does not follow symlinked
directories or Windows junctions. Filesystem checks and subsequent operations are not an
OS-level transactional sandbox.

`GitIgnore` controls visibility in listings and recursive searches, independently of
permissions. It reads repository and nested `.gitignore` files, caches them for the session
and always hides `.git`. An ignored path can still be accessed explicitly if permitted.

Commands use `*` globs; a pattern without `*` also matches a word prefix. Hosts are
case-insensitive and normalize numeric IP literals. Deny rules are checked at use, even
when a requested pattern was previously approved. Each permission decision is included in
the tool result so the model knows whether approval was temporary, session-wide or denied.

## Host operations

Text helpers use UTF-8. `TextFiles` accepts LF, CRLF and CR and preserves the first newline
style and final-newline convention during line edits. Listings return paths relative to the
working directory when possible; API paths use `/` separators on every platform.

`cat` streams bounded line prefixes and prints line numbers. `sed` counts and rewrites in
one pass and rejects zero matches. `replaceLines` returns the replaced text; callers must
account for line-number changes between edits. `copy` and `move` stream data, check both
paths and avoid truncating a file copied onto itself or a hard-link alias. `move` checks
source write permission before copying but is not atomic.

`replaceExact` requires one non-empty literal occurrence and validates it before any write.
It does not interpret regex or replacement escapes. `readRange` stops at the requested
line boundary, with caps of 1000 returned lines, 2000 characters per line and two million
scanned characters. Line-window `cat` also stops once it reaches `to`.

`search` uses lazy descendant traversal and `FileEntryImpl.scanLines`, which closes its
stream when the callback stops or a character budget is reached. `SearchOptions` bounds
matched rows, scanned files, lines per file, line prefixes and characters read per file.
`limited` means a cap was reached, not that another match is known to exist. Regex matching
examines retained prefixes only. Directory listings still use the existing visibility and
sorting rules; traversal does not follow directory symlinks or enter classified trees.

Successful unclassified file operations report `FileChange` through `HostOutput`. Snapshots
read at most 64001 bytes; binary and larger files get a summary without a text preview.
The preview uses common line prefixes/suffixes and one replacement block, capped at 40
lines. It is a compact explanation, not a general diff or undo engine. Classified writes
and deletions never enter this preview path. External-command changes are not automatically
captured by file API callbacks.

`CommandLine` parses quoted arguments, pipelines, `<`, `>`, `>>` and `2>&1`. It rejects shell
control operators and does not expand variables or globs. Explicit argument sequences are
passed verbatim. Each pipeline stage and redirected file is checked separately; a redirection
must be written on the stage it applies to (`<` on the first command, `>`/`>>` on the last),
anything else is refused like the other shell forms rather than silently moved.
`WindowsExecutable` resolves bare commands from absolute PATH entries and validates batch
arguments before launch.

`Processes` drains stdout and stderr concurrently into bounded buffers. Foreground commands
retain prefixes; spawned processes retain recent output. Results use the rightmost non-zero
pipeline exit code. `readUntil` consumes through a regex match; on timeout it throws and
keeps output unread. `waitFor` returns `None` on timeout. Process termination includes
pipeline stages and descendants. Shutdown kills registered processes.

HTTP operations validate the scheme, host and headers, do not follow redirects, and cap
response bodies at 8 MiB. `httpGet` and `httpPost` throw for status codes of 400 or higher;
`httpRequest` returns raw status and body. Classified request handling retains subsequent
transport and response failures within `Classified`.

## Configuration semantics

Layers load in this order: global, nearest project, explicit `-c` file. A project is the
nearest ancestor containing `.atc/config.json` or `.atc/keys.properties`. Duplicate file
paths retain their first role. Project rules are anchored to the directory containing
`.atc` and grant access only within it; their restrictions apply wherever they match.

| Setting | Merge rule |
|---|---|
| Providers | Merge by provider name; model entries merge by alias, replacing a repeated alias |
| Model selection, instructions, other ordinary settings | Later layer wins |
| Commands, hosts | Concatenate across layers |
| File rules | Retain each rule and its layer base |
| Deny commands, deny hosts | Accumulate across layers |
| Mode and numeric limits | Granting layers set values; project layers may only tighten them |
| Safe mode, gitignore visibility | Project layers may enable, but cannot disable, an enabled restriction |

Only explicitly defined project settings narrow a value. A missing timeout means no limit.
`Configuration.rules` is the complete rule list; do not build policy from `settings.files`,
which contains only granting-layer entries. Configuration validation checks modes, limits,
patterns, model references and provider settings before execution.

`Config.combine` first merges ordinary settings in layer order, then obtains policy
settings from granting layers and applies project restrictions. Numeric restrictions use
minimum; enabled safety flags use logical OR. These operations are order-independent for
narrowing layers. A field omitted from a project JSON object is not an explicit request
for its case-class default, which is why `ConfigLayer.defines` participates in tightening.

Key bindings are separate from settings. Lookup uses project files, global files, then the
live process environment; blank values are skipped. New key files use owner-only POSIX
permissions where supported. Windows uses inherited ACLs.

`Config.setTopLevel` preserves surrounding JSON formatting, BOMs and line endings. The
`ObjectText` scanner operates only after JSON validation. Duplicate keys update the final
occurrence, matching ujson's lookup. Writes use a temporary file and atomic replacement
where supported, preserving POSIX permissions and resolving a configured symlink target.

## Models and providers

`ModelCatalog` resolves `provider/alias` or an unambiguous bare alias, ignoring case.
`displayName` affects presentation only. `ChatModel` has streaming `complete` and one-shot
`simple` operations. Provider adapters normalize stop reasons into `CompletionStop`.

`Msg` carries neutral text and tool calls. Assistant messages may also carry a `NativeTurn`
for replay to the exact provider/model reference that produced it. Switching models uses
neutral fields and excludes model-bound reasoning data. `TokenUsage.input` includes cache
reads and writes; adapters normalize provider-specific accounting.

Anthropic requests mark the system prompt and recent user/tool-result content for caching.
Responses requests use stateless replay with encrypted reasoning content. OpenAI-compatible
adapters send vendor `thinking` parameters only when configured. Non-thinking one-shot
requests can retry without a guessed reasoning effort when the provider rejects that
parameter; unrelated bad requests are not retried by this fallback.

Provider SDK request construction remains in each adapter. Shared configuration and client
setup belong in `Providers`; model selection belongs in `ModelCatalog`.

## Conversation context and agent loop

The system prompt contains environment data, workflow instructions, capability rules, the
API source and configured permissions. Session grants are reported in tool results so they
do not change the prompt prefix. Repository-derived scalar values are JSON-quoted; larger
instruction and permission blocks are marked as data.

`CompletionPolicy` selects tool execution, continuation or completion. Calls from truncated
or blocked responses are not executed. Output limits add `Msg.Continuation`; server-side
pauses can resume directly. Resume and tool-budget rejection counts bound repeated work.
The interactive tool budget may be extended by the user; non-interactive runs stop at it.

`TurnOutcome` records why the loop ended. `Finished` means the model produced a final
response, not that the user's overall objective was independently verified. Interruption,
provider refusal, exhausted limits and empty terminal responses have distinct outcomes.
`App` maps them to summary labels and process exit codes; uncaught execution errors remain
exceptions within the loop and are reported as `Failed` by the application.

`ModelRequest` runs at most one unfinished provider call per agent. The provider adapters
use SDK asynchronous streams, register their close callbacks before waiting for completion,
and accumulate events through the existing SDK accumulators. This permits cancellation
before HTTP headers arrive as well as during streaming. The caller polls cancellation every
50 ms, closes the stream and interrupts its worker. A provider that does not stop prevents
another worker from accumulating behind it and produces a clear retry message. Stream
sinks reject late output after the request ends. Provider clients are closed at application
shutdown. Interrupted calls may not supply a final token-usage report.

Cancellation tracks the underlying OkHttp call through a request-scoped event listener.
It calls `Call.cancel()` before closing the SDK's buffered reader: closing that reader alone
can wait on a lock held by a stalled read. The listener captures the request scope so retries
on SDK threads remain cancellable. Temporary SDK clients borrow the model's connection pool
and executor through transports whose `close` is a no-op. Only the owning model closes
these resources. This also prevents SDK garbage-collection cleanup from shutting down the
executor between tool calls. Provider tests cover authentication, cancellation before headers,
cancellation between chunked events, subsequent requests and garbage collection. Error messages
include a bounded cause chain; `ATC_DEBUG=1` adds the complete stack trace.

`Conversation` repairs pending tool results after a failure and inserts assistant markers
when needed. Pending REPL notices are prepended to the next user message. Real user input
and internal continuation messages remain distinct for context fitting and prediction.

`ContextManager` estimates tokens from text and replay payloads, then calibrates against
provider counts. It reserves output capacity and drops complete older exchanges at user
boundaries. The latest exchange is retained. An unavoidable overflow produces a warning
once per user turn. Changing models resets calibration.

The estimator starts at approximately one token per four UTF-16 characters, plus message
framing. For a configured context window `W`, it reserves
`max(W / 8, configured maximum output tokens)`. Completed requests with at least 200 input
tokens update the provider/estimate ratio, clamped to `[0.25, 8.0]`. Estimates include only
native payloads eligible for replay to the selected model and avoid counting native and
neutral assistant content twice. This is an estimate, not provider tokenization.

`Agent.compact` implements `/compact [focus]` with a tool-free request to the selected
model through `ModelRequest`. `ContextCompaction` serializes neutral transcript data and
builds a user/assistant summary exchange, including retained task context. It commits only
complete, nonempty summaries smaller than the older prefix they replace; the result is an
`Agent.CompactOutcome` (compacted, nothing to compact, summary not smaller) so `/compact`
can say which. Pending notes, user request tracking, task state and REPL definitions are
unchanged; usage is recorded separately. Before the summary request is sent, the transcript
is estimated against the model's input allowance (window minus an eighth) and refused with
an actionable message when it cannot fit, since it goes to the same model as one message.

`Agent.autoCompact` runs at the top of every round, after queued input is accepted and
before `ContextManager.prepare` fits the request: before the first request of a turn and
between tool rounds, so a long tool loop can be summarized while it runs, but never between
a tool request and its results (the history would be invalid) and never after the final
answer (a `-p` run would pay for a summary nobody reads). It compares calibrated
next-request usage with `contextWindow * autoCompactThreshold`. This fraction defaults to
`0.8`, accepts `[0, 1]`, and uses zero to disable automatic compaction. It is a non-policy
setting merged with later-layer precedence and shown in `/config`. When the exchange in
progress is itself summarized (nothing fits the retention budget), a
`Msg.Continuation` (`AgentMessages.compactionContinuation`) closes the request so that the
model continues from the summary instead of being asked to complete an assistant message.

Both manual and automatic compaction use `ContextManager.splitForCompaction` to retain the
largest suffix of whole user exchanges within `contextWindow * compactKeepRatio`, divided
by the current token calibration. The ratio defaults to `0.2`, accepts `[0, 1]`, and follows
normal layer precedence. Estimates include native payloads replayable by the current model.
Tool results and continuations cannot create cut boundaries. An oversized latest exchange
is summarized too; an unknown window or zero ratio retains no verbatim suffix. When all
history fits, no summary request is made. The replacement summary precedes the untouched
suffix, and must be smaller than the older prefix. This budget covers only retained history,
not the summary or fixed prompt.

An automatic attempt that fails or produces no smaller summary warns once, leaves the
request to ordinary trimming, and is not repeated until calibrated usage has grown by a tenth
of the window past the failed attempt (`compactRetryAt`, reset by any successful
compaction, including a manual one). Ctrl-C during the summary request interrupts the turn
like any other request, with history unchanged; queued input skips the attempt so the next
round can accept it first. Summary messages persist through the existing session format.

History fitting computes message sizes and prefix sums once, then chooses a real user
boundary. It never cuts between a tool request and its results or drops only part of the
latest exchange. `Msg.Continuation` is excluded from those boundaries so an automatic
resume cannot remove the user request it is continuing.

`HostInteraction` retains immutable `TaskNotes` (goal, constraints, completed work and
remaining steps), limited to 16000 characters. The agent prompt asks it to maintain these
alongside TODOs. `Conversation` separately retains the first user request and up to eight
recent requests, bounded to 8000 characters each. When history is cut, `ContextManager`
reserves room for a JSON representation of the task notes, bounded recent instructions and
TODO state, and inserts it with the cut notice. This leaves the stable system prefix intact.
Current user instructions and actual permissions take precedence over working notes.

Submitted updates enter a concurrent queue. While a model is generating, an update cancels
that request; while Scala is running, it waits for the call to return. Remaining calls in
the old completion receive skipped results. `Conversation.steer` adds the correction as a
user message, inserting an assistant bridge after tool results when needed. The next model
request therefore sees the correction before choosing another operation.

`SessionStore` writes versioned JSON snapshots with neutral messages, pending notes, task
state and TODOs. It excludes SDK replay payloads, REPL definitions and permission grants.
Files are limited to 8 MiB, created exclusively and owner-only on POSIX systems. `/resume [file]`
validates the file before clearing current state, checks tool-call/result pairing, creates
fresh permission and REPL state, and adds explicit notices about lost definitions and
grants. It retains the currently selected model and never executes saved tool calls.

Interactive terminal sessions save on normal exit (`/quit`, its aliases, or Ctrl-D).
`SessionStore.autoSavePath` hashes the canonical working directory with SHA-256 to select
a checkpoint under `~/.atc/sessions/`. This works with read-only project directories and
keeps different working directories separate. Startup offers to resume that checkpoint;
bare `/resume` opens it later. Empty sessions leave it unchanged, so declining the startup
offer and immediately quitting does not erase previous work. Conversation messages, pending
notes from `/run`, task notes or TODOs make a session non-empty. Manual `/save` files remain
independent. Scripted runs and redirected input do not save or prompt automatically.

`checkpoint` writes an exclusive temporary file with the usual size limit and permissions,
closes it, then replaces the previous save with an atomic move. Filesystems without atomic
move support use a replacement move. Failed writes leave the previous checkpoint intact;
temporary files are removed. Concurrent instances in one directory share the checkpoint,
so the last successful save becomes the resume point. A missing or invalid checkpoint never
prevents startup. This is normal-exit persistence; it does not install a shutdown hook or
promise recovery after a forced termination. Restoring state waits for a new user request
before any model or tool runs.

When a permission prompt returns feedback, `ScalaToolRunner` sets `ToolResult.needsReplan`
from the recorded decision, even if the snippet caught the permission exception. `Agent`
then skips the remaining tool calls in that completion and supplies an error result for
each skipped call, preserving the provider's request/result pairing. The next completion
can issue revised calls normally. This scheduling flag is internal and is not serialized
by provider adapters. It does not roll back earlier effects or stop code that continues
inside the same snippet after catching the permission exception.

`InputPredictor` sends recent conversation text to the agent model on one daemon worker.
A generation counter prevents stale publication; at most one job runs and one waits.
Predictions are reduced to visible single-line text and reported separately in usage.

## The terminal

`Tui` owns JLine input, streaming response blocks, tool panels and permission menus.
`Ansi` removes terminal controls from external text before display. Keep model-visible
capture text unchanged; sanitize only at display boundaries. `TextSink` incrementally
handles UTF-8 and BOM-marked UTF-16 process output.

Ctrl-C interrupts a turn. Ctrl-O toggles expanded output; compact mode folds long output
and summarizes reasoning. Shift-Tab cycles sandbox modes. Tab completes slash commands or
accepts a prediction; the right arrow also accepts predictions. Ctrl-D exits.

`Continuation` handles open brackets, strings and comments for `/run`. Shift+Enter and
backslash followed by Enter insert a newline. An empty line submits a code block; Ctrl-C
cancels block input. During a turn, a separate reader collects corrections and unsent
draft text and handles escape sequences, stopping on timeout or EOF. Menu reads pause that
reader.

During a turn, Enter submits a correction and unsent text is shown in the status line.
Bracketed pastes are collected without submitting individual lines. The status line uses
JLine `Status`, updates on phase/input changes, and reserves a terminal row for the active
operation, elapsed time and model/mode/directory context. Spinner writes and status updates
share the TUI lock. The footer is reserved before the first content line, so adding it does
not scroll the banner away. Its activity indicator replaces a separate spinner when the
terminal supports a status line. Idle state shows a short model, mode and directory label;
menus and answer fields replace it with the applicable keyboard controls.
Resize signals update the footer even while a menu has paused the turn's key reader.
Every footer update goes through `Tui.drawStatus`, which flushes the terminal writer after
JLine's `Status.update`: JLine flushes the footer text but leaves the closing
synchronized-update sequence (`ESC[?2026l`) buffered, and a terminal that honours mode 2026
(xterm.js in VS Code, iTerm2, kitty, Ghostty, WezTerm) freezes rendering until it arrives.
Without that flush, a footer repainted from the input-poll clock stalled the window for up to
a poll interval per repaint (`TuiSuite` checks the closer is written).
ASCII mode also selects ASCII menu markers and control separators.
Streaming text is flushed at the end of each incoming update. Nested rendering helpers share
that flush instead of flushing every gutter and style fragment. Live previews compare their
rows with the previous view and immediately repaint only changed rows. They use line erasure,
not erase-to-end-of-screen, so updating a preview cannot erase the footer. Footer work runs on
state changes and the existing input-loop clock; writing text does not trigger a footer redraw.
There is no frame-rate cap or deferred stream queue. `TuiSuite` checks preview growth,
replacement, shrinkage and clearing while preserving a footer outside the owned rows.
Background process events between turns use `LineReader.printAbove`
so notifications do not overwrite the user's input.

`TextLayout` wraps complete lines by terminal cell width, preserving ANSI styles and whole
Unicode code points. It scans long lines once, with word boundaries preferred over hard
breaks. Help and banner fields retain aligned continuation lines; when their value column
would be too narrow, values move below labels. Tool code, diagnostics, permission details
and TODOs use the same wrapping rules. Diagnostic folding counts the resulting display
rows, so a long first error remains readable and `/output` exposes the complete result.

Markdown tables retain their natural columns when they fit. Wider tables cap column widths
and wrap individual cells. A table with three or more columns switches to labeled records
when wrapping would leave fewer than 24 cells per column on average; tables whose minimum
column widths cannot fit use the same fallback. This preserves information without turning
long identifiers and sentences into narrow strips. Completed output panels remove the
Ctrl-O hint once that live view is no longer active.

`ToolHistory` retains up to 20 results within an eight-million-character budget. Each
result retains at most two million output characters plus bounded code and file previews;
live command output is capped separately at one million characters. `/output` displays
200-line windows without evaluating code again. Classified terminal-only output is never
added to the history, and `/new` clears it. These records are for inspection and are not
part of saved conversations.

Permission menus include **Tell the agent what to change**, followed by free-text input.
Empty or cancelled feedback returns to the menu. `Tui.readAnswer` consumes JLine's
preserved input-cancellation interrupt so the next menu can read normally.
Plain prompts accept exact `y`/`yes` or
`s`/`session` approvals, exact `n`/`no` denials, and treat other non-empty input as
`Decision.Revise`. In particular, a qualified answer starting with “yes” or “skip” must
not become an approval by prefix matching. EOF and empty plain replies deny the request.

Question menus always include a custom-answer option. For multiple selections, chosen
answers retain their display order and custom text is appended. User input is returned to
the host's `ask` call; only its terminal display is sanitized.

`MarkdownStream` incrementally renders supported Markdown and buffers tables until column
widths are known. `Highlight` uses the compiler's Scala scanner. Layout calculations use
terminal cell widths. History is owner-only on POSIX systems and rejects final symlinks.
Non-interactive runs use a dumb UTF-8 terminal and do not prompt for permission unless
explicitly configured to auto-approve requests.

## Testing and conventions

Tests use munit under `app/test/src/atc`. Extend the suite responsible for the behavior:

- `CapabilitySuite`, `ModeSuite`, `SandboxSuite`, `ReplSessionSuite`: compiler boundaries,
  capability requirements, REPL state and interruption.
- `PolicySuite`, `PermissionSuite`, `HostSuite`, `ClassifiedSuite`: permission rules and
  host effects, including classified values and local HTTP requests.
- `HostEditingSuite`: literal replacements, bounded reads and searches, file previews and classified exclusions.
- `ModelRequestSuite`, `ProviderCancellationSuite`: cancellation and HTTP client ownership across requests.
- `SessionStoreSuite`: portable conversation persistence, validation and file permissions.

The **run test** (`tests/run_test.sh jar|native|cmd <command>`) drives the built program
rather than the classes: `tests/mock-llm/server.py` (standard library only) speaks the
OpenAI Responses, OpenAI chat-completions and Anthropic Messages protocols over SSE and
answers every conversation the same way, thinking, a `run_scala` call with the code from
the user's `run: ...` message, then a text answer quoting the tool result, with fixed usage
numbers. The harness starts it on a free port, writes a config with one provider per
protocol, redirects `HOME` so the developer's own config stays out, runs one `-p` turn per
adapter and asserts on the transcript (thinking shown, the call, the sandbox result, the
final answer, the usage). It is what CI runs against the jars and against each native
binary, and the way to exercise an adapter's streaming path without an API key: the mock's
streams carry exactly what the SDK accumulators need, so an SDK upgrade that tightens
parsing fails here first.
- `ConfigSuite`, `LayerSuite`, `ModelSuite`, `GitIgnoreSuite`: configuration and lookup.
- `AgentCoreLoopSuite`, `AgentLoopSuite`, `CompletionPolicySuite`, `ContextManagerSuite`:
  loop decisions, transcript repair, context fitting and the real REPL integration.
- `TuiSuite`, `TextLayoutSuite`, `ToolHistorySuite`, `RenderSuite`, `InputPredictorSuite`, `DebugSuite`:
  terminal helpers, retained output, rendering, prediction and error reporting.
- `ReplInterruptionSuite`: cancellation recovery in an isolated compiler process.
- `ProcessesSuite`, `PlatformProcessSuite`, `TextFilesSuite`: process and platform behavior.

`TestEnv` supplies temporary directories, scripted permissions and recording host ports.
`ReplAssertions` checks snippets. Prefer `ProcessFixture` over host shell commands for
portable process tests; reserve native commands for platform integration suites.
`tests/atc_test.sh` uses temporary files and stubbed downloads/Java to test the Unix wrapper
and checkout environment loading.

All Scala modules use explicit null checks where configured; Java APIs may require `.nn`.
Use `Platform` and `PlatformPath` for OS decisions and `ScalaSource` for generated Scala
literals. Keep model/provider escaping, shell quoting and terminal sanitization separate.
Scalafmt uses a 120-column configuration that preserves existing layout. `Interface.scala`
and `Runtime.scala` are excluded because the formatter cannot parse their capture-checking
syntax; format them by hand. Preserve tests for capability contracts when editing them.

## Wrappers, releases and CI

The Unix `atc` wrapper installs to `~/.local/bin` and caches JARs in `~/.atc/jars`.
`ATC_INSTALL_DIR` and `ATC_CACHE_DIR` override those locations. Release downloads require
SHA-256 digests for both JARs. Metadata uses jq when available and a field-order-dependent
fallback otherwise. A cache marker records `release-id|tag`; verified updates replace it
with the downloaded artifacts. Uninstall validates the cache root and removes ATC-owned
artifacts while retaining configuration and unrelated files.
Directory-name prefixes alone do not prove ownership: uninstall retains directories such
as `dev.notes` and `download.notes`, including in a custom cache location. Temporary
download and development directories are cleaned by the operation that creates them.

Before launching Java, `cmd_run` calls `offer_startup_update`. The check requires terminal
stdin and stderr, a recognized installed release marker, and an interactive application
invocation. `ATC_CHECK_UPDATES=0`, `-p`, help/version and initialization flags bypass it.
GitHub metadata uses a two-second connection timeout and a five-second total timeout;
lookup failures are ignored. Stable `vMAJOR.MINOR.PATCH` tags are compared numerically,
and a release without both JAR assets is not offered. Unknown and development markers
are left for explicit `atc update` handling.

The prompt defaults to No. Approval passes the already-fetched metadata to
`download_latest_release`, so the updater installs the release the user approved without
another lookup. Download and checksum errors stop that attempted upgrade before Java
starts; declining or a failed metadata check starts the current installation. The updater
checks critical failures explicitly because Bash conditional callers can disable `errexit`.

`atc dev <checkout>` copies an existing local distribution and records `dev|<checkout>`.
It does not build. `atc update` replaces that development installation with a release.
Windows updates replace the launchers and JARs together. Native Windows launchers transport
Unicode application arguments through private `ATC_INTERNAL_*` environment variables;
ATC removes those variables from tool-process environments.

CI has three jobs. `build` runs on Linux, macOS and Windows: the Bash wrapper tests (`atc`
and `atcn`, Unix only), formatting (Linux), `dist`, the application tests and the run test
of the jars against the mock LLM server; Linux uploads the jars as the `atc-jars` artifact.
`native` builds the native image on one runner per target from that artifact, run-tests it
and uploads `atc-native-<target>`; it runs on every push and pull request too, so a change
that breaks the image is caught before a release. `publish-release` only downloads the
artifacts and attaches them to the release; nothing is rebuilt for publishing. Published release tags must match
`Versions.atc` (with an optional `v` prefix). The release job builds and uploads the two
JARs and Windows launchers after the platform jobs succeed.
