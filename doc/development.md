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
| `ATC_JAVA_OPTS` | Additional JVM flags for every launcher, applied after the defaults and before the command line's `-Xmx`/`-Xms` |
| `-Xmx<size>`, `-Xms<size>` (launcher arguments) | JVM heap flags that `atc`, `start.sh`, `start.ps1` and `atc.ps1` take out of the arguments (the value of an option such as `-p` is never taken for one) and pass to `java` after the defaults and `ATC_JAVA_OPTS`, so they win; ATC never sees them |
| `ATC_STARTUP_CACHE=0` | Run without the JVM startup cache (`atc`, `start.sh`) |
| `ATC_DEBUG` | Enable stack traces and stream/terminal diagnostics when set; logged text has terminal controls removed |
| `ATC_ASCII` | Select ASCII terminal glyphs when set |

### JVM settings

Every launcher starts the JVM with `-Xms256m -Xmx2g -Xss4m -XX:-UsePerfData`
(`DEFAULT_JVM_OPTS` in `atc`; the same list in `start.sh`, the dist `atc` script, `atc.ps1`
and `start.ps1`), and on Java 23+ `--sun-misc-unsafe-memory-access=allow`
(`VERSIONED_JVM_OPTS`; the dist `atc` script leaves it out since it does not detect the Java
version), because Scala's `LazyVals` still use `sun.misc.Unsafe` and JEP 471 makes the JVM
print four warning lines on every run otherwise. On Java 24+ the same list adds
`--enable-native-access=ALL-UNNAMED`, because JLine loads its native terminal library and
JEP 472 prints four warning lines for that. Measured with the dist jar and the echo
model (JDK 25, September 2026):
a 100-turn session of reads, writes and commands keeps 130 to 230 MB live and runs down to
`-Xmx192m`, so 2 GB is headroom, and it bounds a runaway sandbox computation (one line
building a large vector reached 5 GB under the JVM's own quarter-of-RAM default; under 2 GB
it fails in five seconds with an out-of-memory error the session survives). With the 1 MB
Linux default thread stack a 3000-term expression overflowed the compiler's stack before its
own recursion guard could report it; `-Xss4m` costs nothing since stacks are only reserved.

The **startup cache** halves the cold start (2.6 s to 1.3 s on that machine, and 30% less
CPU over a session, since the JDK 25 cache carries method profiles): on Java 25+ an AOT
cache (`-XX:AOTCacheOutput=` to build, `-XX:AOTCache=` to use), on Java 19 to 24 a dynamic
CDS archive (`-XX:ArchiveClassesAtExit=` / `-XX:SharedArchiveFile=`). Both are bound to
the exact jars and JDK, and a stale one makes the JVM print error lines and (for CDS) is
*not* regenerated, so the launchers never point the JVM at one that might be stale:
`~/.atc/jars/startup/key.txt` (`out/dist.dest/startup/` for `start.sh`) records the JDK's
`-version` output (read once per run by `ensure_java`, kept in `JAVA_VERSION`), the release
marker and the version-gated JVM options (the JVM refuses a cache built under other module
options, such as `--enable-native-access`), its timestamp is compared with the jars
(`find -newer`; it is dated like a newer jar so a future-dated jar cannot force a rebuild
on every run), and a mismatch triggers one silent echo-model `-p 'run: 1 + 1'` run with the
building flag. A build that leaves no file writes the key anyway, so it is not retried until
the JDK or release changes. `atc self uninstall` removes the cache with the jars.
`-XX:TieredStopAtLevel=1` (60% less CPU, 50% slower steady-state compiles) and
`-XX:+UseSerialGC` (200 MB less resident memory, five times the GC time, pauses still under
10 ms) were measured and left to `ATC_JAVA_OPTS`.

For development without packaging, use `./mill -i app.run`. For manual REPL checks,
`./mill app.test.runMain atc.Scratch file.scala` evaluates snippets separated by `// ---`.
An `echo` provider supports local testing: `run: <Scala code>` invokes the REPL; other
requests are echoed. It needs no API key or network connection.

## Architecture

| Component | Responsibility |
|---|---|
| `lib` | Agent-facing capability types, data types and `Interface`; compiled with capture checking |
| `app` | Configuration, models, permissions, host operations, REPL and terminal |
| `app` root package | `Main` and `Cli` parse the command line; `App` wires the parts together and runs the loop; `Setup` and `FirstRun` run the first start; `Models` holds the catalog and clients; `SandboxRepl` owns the REPL session |
| `commands/` | `SlashCommand` is the table of commands; `Commands` parses, dispatches and completes them; `ModelCommands`, `ProvidersMenu`, `SessionCommands` and `StatusCommands` implement them |
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
`ToolOutput` appends one hint per result: most are keyed on the output of a failed run (a safe-mode
rejection, a missing capability, a command that could not start), one on the snippet
itself (`ToolOutput.codeHint`): a `\"` inside a plain triple-quoted literal, which stays a
backslash and a quote, so a Python docstring written as `\"\"\"` lands in the file with
backslashes; live runs of several models did this and spent rounds repairing it. The system
prompt says the same in its editing rule, and its Environment block tells the model whether
a user is present (`AgentEnvironment.userPresent`, false for a `-p` run, where `ask`
returns `None` and permission prompts fail unless `--approve-all` was given), so the model
decides for itself instead of asking nobody. The REPL echo of a `ProcessResult` shows the
exit code and the size of each stream only (`toString` in `Interface.scala`), because a
snippet that prints the streams and ends with the value used to send them twice.

`Host` implements `Interface` directly through file, process, network and interaction
traits; `HostPaths` holds the path resolution and permission checks they share. `HostOutput`, `HostLlm` and `HostUi` are dependencies supplied by `App` or tests.
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
function must preserve this dependency. The default-argument regression discussed below
follows from it.

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
`Exec` is already a full capability. The bare-`FileSystem` convention does not extend to
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

| Mode | Machine capabilities supplied by the preamble |
|---|---|
| `readonly` | `io: IOCap`, `fs: FileSystem^{io.rd}` |
| `local` | `io: IOCap^`, `fs: FileSystem^{io}`, `ex: Exec^{io}` |
| `full` | the local capabilities plus `net: Network^{io}` |

Every mode also supplies `user: UserIO^`, which is independent of the machine root, so
output, questions, TODO updates and normal `chat` calls remain available in read-only mode.
`Exec` and `Network` have no read-only view. Command operations require both `Exec^` and
`FileSystem^`, including when redirection writes a file.

Capability constructors are private to ATC. `Runtime` and `Derivations` provide the
sandbox's internal bootstrap API and are marked `@rejectSafe`. Agent code cannot derive
missing capabilities from a root it holds.

The preamble loads each given in a separate REPL round. Each therefore occupies a separate
wrapper class: a read-only operation capturing `fs` does not also capture the full `user`
capability. Keep the givens at the top level; grouping them in an object changes capture
checking behavior.

### Classified data

`ClassifiedImpl` stores a `Try`: non-fatal computation failures remain confidential.
HTTP calls with classified headers or bodies return classified responses, preventing a
server from reflecting a secret into ordinary output. Validate public parameters and
permissions before inspecting classified values, and keep subsequent failures inside the
classified result or user-only output.

The data-flow argument relies on both parts of the API. `map` does not expose a plain
result, and its callback cannot capture a full output capability. Deriving a Boolean from a
secret therefore keeps the Boolean classified too. Allowing `println`, a mutable file
handle, or an untrusted model callback inside `map` would expose information even if the
callback returned a harmless value.

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

`SandboxRepl.warm` starts the REPL on a daemon thread as soon as the program is ready for
input (after the resume offer; before the turn of a `-p` run), because compiling the
preamble takes about two seconds that would otherwise land inside the first tool call.
`SandboxRepl.ensure` adopts that session on the first Scala tool call or `/run`, waiting for
it (with a "starting sandbox" status) only when it is not ready yet, and starts one on the
spot if none is warming. `SandboxRepl.replace` (reset, mode change, `/new`) drops the live
session and a warming one alike (`discardWarming`: a daemon thread closes it once its
initialization ends, so the switch never waits for a compiler it no longer needs) and
starts warming the next one. Text-only turns never wait for a compiler. Reset and mode changes discard the previous
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
process or network effects are not rolled back after interruption or timeout. A timed-out
result still carries what the snippet printed before the limit (the worker's output if it
stopped within the two-second grace, else a snapshot of the capture buffer it is still
writing to), minus the stop signal's trace, so the model sees the progress made; an
interrupted result stays empty, since the turn ends anyway.

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

`/reset` and `/mode` reset the REPL and its spawned processes, `/mode` with the newly
selected capabilities. `/new` resets that and the conversation, queued notes, usage
accounting, task notes, TODOs, retained output and session grants, and clears the window
and its scrollback; the footer is drawn again from nothing.
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
OS-level transactional sandbox. Canonicalization corrects the case of existing names only, so
on Windows and macOS (`Platform.caseInsensitivePaths`) globs match case-insensitively: a new
`.ATC` is the same directory as `.atc` there and must fall under its locked rule. An agent path
on a UNC share other than the working directory's is refused before canonicalization, which
would otherwise contact the server even from a read-only file system.

`GitIgnore` controls visibility in listings and recursive searches, independently of
permissions. It reads repository and nested `.gitignore` files, caches them for the session
and always hides `.git`. An ignored path can still be accessed explicitly if permitted.

Commands use `*` globs; a pattern without `*` also matches a word prefix. Hosts are
case-insensitive and normalize numeric IP literals. Deny rules are checked at use, even
when a requested pattern was previously approved. Each permission decision is included in
the tool result so the model knows whether approval was temporary, session-wide or denied.

### Request blocks from the agent's side

`requestFiles` works in every mode: the file system it lends the block is exactly as
capable as the one the caller already holds, so read-only callbacks remain read-only, and
command execution, which needs `FileSystem^`, still compiles only in local and full mode.
`requestExec` widens only `Exec^`; a command that also needs a file permission that is not
configured needs a nested `requestFiles` block. The result of a request tells the agent
what the user decided, so "once" needs another request next time while "for the session"
does not. In the pop-up, **Tell the agent what to change** sends the typed text back
instead of a grant: the original request receives nothing, the remaining tool calls of that
batch are skipped, and the text survives result truncation. Without a menu-capable terminal
the answer is `y`, `s` or `n`, or free text; only an exact approval grants ("yes, except
the deployment command" is feedback, not approval).

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
scanned characters. Both `cat` forms show at most 400 lines and suggest a continuation
when more remain. The range form also stops at `to` or two million scanned characters;
it reports a read limit separately from end of file.

`search` uses lazy descendant traversal and `FileEntryImpl.scanLines`, which closes its
stream when the callback stops or a character budget is reached. `SearchOptions` bounds
matched rows, scanned files, lines per file, line prefixes and characters read per file.
`limited` means a cap was reached, not that another match is known to exist. Regex matching
examines retained prefixes only. Directory listings still use the existing visibility and
sorting rules; traversal does not follow directory symlinks or enter classified trees.

Successful unclassified file operations report `FileChange` through `HostOutput`. Snapshots
cover files of at most 64000 bytes; binary and larger files get a summary without a text
preview. The preview uses common line prefixes and suffixes and one replacement block,
capped at 40 lines. It is a compact explanation, not a general diff or undo engine.
Classified writes and deletions never enter this preview path. External-command changes are
not captured by file API callbacks.

`CommandLine` parses quoted arguments, pipelines, `<`, `>`, `>>` and `2>&1`. It rejects shell
control operators and does not expand variables or globs. Explicit argument sequences are
passed verbatim. Each pipeline stage and redirected file is checked separately; a redirection
must be written on the stage it applies to (`<` on the first command, `>`/`>>` on the last),
anything else is refused like the other shell forms rather than silently moved.
`WindowsExecutable` resolves bare commands from absolute PATH entries and validates batch
arguments before launch. Commands do not inherit the variables that hold provider keys
(`Configuration.keyVariables`: the `${VAR}`s of `key` and `headers`, `keyEnv`, and the SDK's
default variables for a provider that names no key), since a command that prints its
environment would hand them to the model.

`Processes` drains stdout and stderr concurrently into bounded buffers. Foreground commands
retain prefixes; spawned processes retain recent output. Results use the rightmost non-zero
pipeline exit code. `readUntil` consumes through a regex match; on timeout it throws and
keeps output unread. `waitFor` returns `None` on timeout. Process termination includes
pipeline stages and descendants. Shutdown kills registered processes.

`parallel(tasks)` runs the tasks (a `Seq[() => A]`) on a pool of at most eight daemon
threads created for that call (a nested call gets its own pool, so it cannot starve the
outer one). The results come back in task order once every task has ended; a failed task
does not cut the others short, since their effects must not outlive the snippet, and the
first failure in task order is rethrown, with a fatal throwable winning over ordinary exceptions so that the
REPL's stop signal reaches the evaluation thread. The stop flag is checked by every
instrumented REPL class on every thread, so an interrupt or timeout stops busy tasks too;
for tasks blocked in a host call, `parallel` interrupts its workers when the calling thread
is interrupted and reports the interruption. Nothing else in the host changes for it, but the
signature matters: the tasks are declared `Seq[() ->{C} A]` with a capture-set parameter
`C^`, because a function inside a `Seq[() => A]` is *boxed*, and a boxed closure's captures
are charged only when it is unboxed, which happens inside the trusted host. With the plain
signature, `classify(s).map(s => parallel(List(() => exec("echo", List(s)))))` compiled and
ran the command on the secret (`CapabilitySuite` keeps that snippet as a regression test);
the capture-set parameter makes the checker charge the caller with `C`, so `Classified.map`
keeps its rules and pure tasks still compile inside it. The TUI's output methods are already synchronized, `Policy` and the
process registry lock their mutable state, and `Tui.popupBlock` takes a lock so that
questions and permission prompts from several tasks reach the terminal one at a time (a
waiter interrupted meanwhile never shows its pop-up).

HTTP operations validate the scheme, host and headers (secret header names too; only their values stay inside the classified boundary), do not follow redirects, and cap
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
| Providers | Merge by provider name; model entries merge by alias, replacing a repeated alias. A project layer may set only `models` and `enabled` of a provider a granting layer defines |
| Model selection, instructions, other ordinary settings | Later layer wins |
| Commands, hosts | Concatenate across layers |
| File rules | Retain each rule and its layer base |
| Deny commands, deny hosts | Accumulate across layers |
| Mode and numeric limits | Granting layers set values; project layers may only tighten them |
| Safe mode, gitignore visibility | Project layers may enable, but cannot disable, an enabled restriction |

A project layer's `commands`, `hosts` and `classifiedModel`, and its `keys.properties`,
reach beyond its own files, and a cloned repository can ship them. `ProjectTrust` records a
SHA-256 fingerprint of exactly these per project root in `~/.atc/trusted-projects.json`
(owner-only); `Config.load` leaves them out while the fingerprint is not recorded, so every
reload agrees with the decision. `Setup.load` shows what they grant, escaped like permission
details, and offers Trust, Run without these grants, or Quit; it says whether the config is
new or has changed since it was trusted. A `-p` run warns and goes without them unless
`--approve-all` is given, which takes them unrecorded. Configs ATC writes itself (`--init`,
the offered starter config) are trusted as they are written, and a `/model`, `/effort` or
`/classifiedmodel` save keeps a trusted project trusted. Other edits, such as a new
`model`, do not change the fingerprint.

Only explicitly defined project settings narrow a value. `executionTimeoutMs` defaults to
300000; a JSON `null` clears it and means no limit.
`Configuration.rules` is the complete rule list; do not build policy from `settings.files`,
which contains only granting-layer entries. Configuration validation checks modes, limits,
patterns, model references and provider settings before execution.

`/config` (`ConfigCommands`) changes only `predictInput`, `notifications`, `webSearch`,
`autoCompactThreshold` and `compactKeepRatio`: settings outside `PolicyKeys`, so none can
loosen the sandbox, and a project config may set them. Every change goes into a `session`
layer that `Models` keeps in memory and appends after the files on each reload, so a
`/providers` edit does not drop it. Saving to the project config in force or to the global
config also writes that file with `Config.setTopLevel`; a failed reload restores the file.
The note on a saved value names a later file that sets the same key and wins at the next
start. `App.useSettings` applies the combined settings to the agent, the notifier and the
predictor, and `Models.reload` switches web search on the cached clients
(`ChatModel.useWebSearch`); clients are not recreated, since a Claude Code client holds a
CLI session. The sandbox keeps the policy it started with.

`Configuration.combine` first merges ordinary settings in layer order, then obtains policy
settings from granting layers and applies project restrictions. Numeric restrictions use
minimum; enabled safety flags use logical OR. These operations are order-independent for
narrowing layers. A field omitted from a project JSON object is not an explicit request
for its case-class default, which is why `ConfigLayer.defines` participates in tightening.

Key bindings are separate from settings. Lookup uses project files, global files, then the
live process environment; blank values are skipped. New key files use owner-only POSIX
permissions (`0600`, with a warning at load when group or others can read the file).
Windows uses the directory's inherited NTFS ACL, which ATC does not rewrite or audit; keep
the file under the private profile and check it with `icacls` on a shared machine.

`Config.setTopLevel` preserves surrounding JSON formatting, BOMs and line endings, and
`Config.editFile` leaves a file alone when an edit changes nothing. The
`ObjectText` scanner operates only after JSON validation. Duplicate keys update the final
occurrence, matching ujson's lookup. Writes use a temporary file and atomic replacement
where supported, preserving POSIX permissions and resolving a configured symlink target.

### Configuration reference

The user-facing settings not covered by the README.

**Providers.** `api` is `anthropic`, `openai-responses` (also DeepSeek and other services
through `url`), `openai` (Chat Completions: Ollama, vLLM, OpenRouter, …), `chatgpt` (the
models of a ChatGPT plan, signed in through the browser; see
[Models and providers](#models-and-providers)), `claude-code` (the models of a Claude plan,
through the user's signed-in Claude Code CLI; same section) or `echo` (keyless, for smoke tests). `key` is a literal or `${VAR}`, and `keyEnv` names a variable;
variables resolve from the project's `.atc/keys.properties` (once the project is trusted),
then `~/.atc/keys.properties`, then the environment. Without `key` or `keyEnv`, the SDK's own
variable (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`) applies only when `url` is unset; a gateway
at a `url` that needs that key names it, as in `"key": "${ANTHROPIC_API_KEY}"`. `headers` are extra HTTP headers for every request; a value may be a
`${VAR}` or `${ATC_SESSION}`, a random id of the conversation (renewed by `/new`) for
gateways that route by session, such as OpenCode
(`"x-opencode-session": "${ATC_SESSION}"`). Requests identify ATC as `atc/<version>` unless
`headers` sets `User-Agent`.

`reasoningStyle` describes a Chat Completions provider (`api: openai`) that asks for
reasoning and returns it in its own way. `request` is a JSON object merged into the body of a
call that reasons, in place of `reasoning_effort`; a string `{effort}` in it becomes the
model's current effort, and with no effort chosen the member holding it is left out.
`requestOff` is merged into calls that should reason little (next-request prediction).
`tags` names the opening and closing tag around reasoning written into the answer text:
what is between them streams as reasoning and stays out of the answer and the history. The
Gemini preset uses all three:

```json
"reasoningStyle": {
  "request": { "extra_body": { "google": { "thinking_config":
    { "include_thoughts": true, "thinking_level": "{effort}" } } } },
  "requestOff": { "extra_body": { "google": { "thinking_config": { "thinking_level": "low" } } } },
  "tags": ["<thought>", "</thought>"]
}
```

A model's own `thinking` switch still applies beside it. Reasoning in the `reasoning_content`
or `reasoning` fields of a delta streams as reasoning whatever the style says.

**Models.** A model is an alias with a provider-specific `name` and its own settings:
`contextWindow` (the real window, so the conversation is compacted and trimmed to fit),
`maxTokens`, `temperature`, `reasoning`, `efforts`, `thinking`, `reasoningSummary`,
`webSearch`, `webSearchVersion` and `displayName`. Name a model by its alias, or
`provider/alias` when two providers share one. A provider whose `models` is empty or absent
lists its own, each named `provider/model-id`: the last list each provider returned is kept
in `~/.atc/model-lists.json`, a new one is fetched in the background once a session has
started, and a provider that cannot be reached, or whose configured key is unset, is
skipped. Anthropic's and DeepSeek's lists supply context windows and effort levels (DeepSeek's
also the default effort), OpenRouter's and vLLM's the context window. A listed output limit
is not taken, since it would be sent with every request and reserved from the window. Without `-m` or `model`, a session starts with the model last chosen with
`/model`.

**First run.** An interactive start without `~/.atc/config.json` or `-c` runs `FirstRun`:
the user chooses one of the `atc/providers.json` presets, gives its key unless one is
already bound (read by `Tui.askSecret`, masked and kept out of the prompt history) or signs
in (`chatgpt`, see `FirstRun.signIn`; `claude-code` needs `claude auth login` beforehand), and
chooses a model from the provider's list, which also checks the key. `Setup.load` then
writes a global config naming only that provider, binds the key in
`~/.atc/keys.properties` (`KeyBindings.bind`, owner-only, other lines kept), stores the
list and records the model as the last one, so the session starts without another fetch.
*Configure providers myself* writes the starting config with every preset and exits;
*Not now* or Esc runs on the built-in starting config. `-p` runs and terminals without
menus never ask.

**Turning providers and models on and off.** `"enabled": false` on a provider or a model
hides it; a disabled provider is never listed. `/providers` edits these switches and the
model entries in place (`ObjectText.withMember` keeps the file's formatting). Choosing models
for a provider that lists its own writes the ticked ones as entries with the context
window, efforts and display name the list reported, which makes them its shortlist;
*Offer every model it lists* drops the entries again. A listed id with `/` gets an alias
without one (`ProviderEdits.aliasFor`). Each edit goes to the last layer holding the
longest part of its path (`ProviderEdits.owner`), because layers merge a model entry as a
whole; a new provider goes to the global config. The configuration is then loaded again
(an invalid result restores the files) and the catalog rebuilt; a model in use that the
change renamed moves to its new name. A provider or model in use, by the session or as
`model`/`classifiedModel`, cannot be turned off. A `chatgpt` provider also offers *Sign in
with ChatGPT*, which replaces the saved sign-in.

**Efforts.** `reasoning` is the effort a session starts with; `efforts` lists the ones the
model accepts (by default every effort its api knows: `low` to `max` for Anthropic, `none`
to `max` for OpenAI). `/effort [level]` switches the agent model's effort, and `default`
sends no effort. The choice is saved as the top-level `effort` of the working directory's project
config (when it has one, like `/model`'s `model`), which replaces the starting model's
`reasoning` in later sessions; an effort that model does not take is ignored with a
warning. `/model` starts the new model at its configured effort and removes `effort`.

**Web search.** A model's `webSearch` turns on the provider's own search tool; the
top-level `webSearch` does so for every model that does not set its own. It applies in full
mode only (`Models.useMode`), since read-only and local mode keep the agent off the network. It is best effort:
when a provider rejects the tool, the model continues without it for the session.

**Notifications.** `notifications` is `auto` (the default: the terminal's own notifications
in kitty, iTerm2, WezTerm, Ghostty and foot, a desktop notification on a local machine, the
bell otherwise), `system`, `terminal`, `bell` or `off`. An alert comes only after a wait in
which the user neither typed nor had the terminal focused: ten seconds for a question or
permission request, thirty for a finished turn.

### File rules and command patterns

Each file rule has a `path` pattern and may specify `access` (`none|read|write`),
`classified`, and `locked`. Patterns follow gitignore-style conventions. A pattern without
`/` matches a path **component** anywhere (`.env`, `*.pem`, `node_modules`). A relative
pattern containing `/` is resolved against the working directory, or the project directory
in a project configuration, and may contain `*`, `**`, `?`, or `[…]`. Absolute paths and
paths beginning with `~/` remain absolute; `.` denotes the working directory itself. A
rule applies to the matched path **and its entire subtree**. Effective access is the
**minimum** granted by all matching rules, and no match means no access. A path is
classified or locked if any matching rule says so, and a deeper rule can only make access
more restrictive.

**Classified** content is only observable as `Classified[String]`, and a classified
directory's structure is classified too (listing it needs `childrenClassified`/`walkClassified`;
`walk`/`grepRecursive`/`find` do not descend into it). A plain `write` to a classified path
is refused, and so is `writeClassified` to a non-classified path. **Locked** means no prompt
can widen the rule. `"respectGitignore": true` (the default) additionally hides what git
ignores from listings; that is visibility, not permission, so an ignored file is still
readable by name.

`commands` contains patterns matched against the complete command line. `*` is a wildcard,
and a pattern without `*` matches by word prefix (`"git status"` allows `git status --short`
but not `git statusx`). A command also needs read access to the directory it runs in. A
pre-approved command runs with the user's privileges and is *not* subject to the file rules.
`hosts` are glob patterns on host names; only `http`/`https` URLs are accepted and redirects
are not followed. `denyCommands` and `denyHosts` use the same syntax: a deny rule overrides
every allow rule, including a session grant, an open `request*` scope, and `--approve-all`.
The template's shell denials are bare names: `"bash"` blocks both `bash` and `bash -c ...`,
but not an explicit `/bin/bash`, a wrapper, or a renamed interpreter; no finite deny list
can classify every program that might execute code. The project template pre-approves only
git commands whose options cannot reach files outside the repository once `--output` is
denied: `git diff` reads any file when one of two paths lies outside the repository or the
directory is not one (`git diff .env /dev/null`), and `git blame` through `--contents`,
`--ignore-revs-file`, `-S` and their abbreviations, so neither is in the template.

**Windows.** Use `/` separators in configuration on every platform: in JSON,
`"C:/Users/alice/project"` (a native backslash starts a JSON escape, so the equivalent form
needs doubled backslashes). The file API accepts native Windows input too, but renders
Windows separators as `/`. Command availability is platform-specific: `./mill`, `ls`, `cat`
and `bash` are not normal Windows commands; use an installed executable or the project's
`mill.bat`. `exec` does not send its command line through a shell; an authorized
`.cmd`/`.bat` launcher inherently uses the Windows command processor with strict argument
checks, and built-ins such as `dir` or PowerShell cmdlets need an explicitly permitted
shell, which grants that shell broad authority. Quote every argument containing spaces; a
backslash remains a path separator, not a space escape. External programs choose their own
newline and encoding conventions (commonly CRLF, sometimes BOM-marked UTF-16 on Windows).

## Models and providers

`ModelCatalog` resolves `provider/alias` or an unambiguous bare alias, ignoring case.
`displayName` affects presentation only. A provider without configured models is listed
through `ChatModel.listModels` (the adapter's own client, with `Providers.ListTimeout`), at
most once per catalog and in parallel across providers: `App` starts `refresh()` after the
banner, and `models` waits for it at most 30 seconds (`ModelCatalog.ListWait`), since a Claude
Code listing can take minutes; a provider whose fetch has not finished keeps its stored list
until it does. Lookups never wait: they use this session's list when
it has arrived, else the one `ModelListStore` kept (`~/.atc/model-lists.json`, reused only
for the same provider name, api and url), else take `provider/model-id` as given. A model
taken as given keeps its unknown context window until a later session finds it in the
stored list. Configuration validation does not check `model` or `classifiedModel`. At the start,
`Models.configured` resolves them, and one that names no model is ignored with a warning
that names its file: the session falls back to the last model chosen or the first one, and
sends classified data to no model. A `-m` that names no model stops the start. A failed fetch is not reported (only logged with `ATC_DEBUG`) and leaves the stored list in use. Listed models are
always labelled by their full reference, so fetching a list never changes the labels of
configured models. `ChatModel.effort` is mutable per client and read at request time;
`Models` caches one client per reference, so `/model` sets the chosen client's effort back
to `ChatModel.defaultEffort` (its `reasoning`).

Web search is best effort. `ModelCatalog` gives every model without its own `webSearch`
the top-level one, listed models included. `SpecModel.withWebSearchFallback` resends a
streaming request without the tool when the provider answers 400 or 422 naming web
search before anything was streamed, and turns the tool off for that client. A gateway
that drops the tool silently cannot be detected. `ChatModel` has streaming `complete` and one-shot
`simple` operations. Provider adapters normalize stop reasons into `CompletionStop`;
`model_context_window_exceeded` (Anthropic, Claude Code) counts as truncation, as an output
limit does, so the calls of a response cut by the context window are not run.

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
setup belong in `Providers`; model selection belongs in `ModelCatalog`. Without a configured
key, the Anthropic and OpenAI adapters take their SDK's credentials from the environment only
for the provider's default endpoint; a provider with a `url` gets the placeholder key `none`
instead, so those credentials never reach another host. A streaming request has no limit on
the whole call, since a long answer can stream for longer than `Providers.RequestTimeout`
(15 minutes); connecting, and each read and write, keep their timeouts, and Ctrl-C cancels
the call. One-shot calls keep the 15-minute limit.

A provider's `headers` are extra HTTP headers for every request to it. `KeyBindings.headers`
resolves `${VAR}` values through the key bindings (an unset variable drops the header) and
keeps the placeholder `${ATC_SESSION}` (`ProviderConfig.SessionRef`), which `Providers.headers(spec)`
replaces per request with the conversation id, a UUID that `Agent.clear()` renews. The same
call adds `User-Agent: atc/<version>` unless the config sets one, regardless of header-name
case. Every adapter applies the set to both `complete` and `simple` with the params builder's
`putAdditionalHeader`. Provider-specific headers such as OpenCode's `x-opencode-session`
are configured explicitly.

Chat Completions requests set `stream_options.include_usage` so providers can report token
counts for `/cost` and context calibration. Providers send usage on the finish chunk,
in a separate chunk, or both. `OpenAIChatModel.ChunkFeed` saves the latest usage and feeds
it to the SDK accumulator once, after the choices. It ignores empty chunks without usage
and extra choice chunks after completion. Auxiliary chat calls also apply configured
`maxTokens` and `temperature`. `ModelSuite` checks chunk handling; `ProviderRequestSuite`
checks requests and usage accounting against a local HTTP server.

Gemini's OpenAI-compatible endpoint streams each tool call whole, without the `index` the
SDK accumulator needs, so `ChunkFeed` numbers such fragments: one with an id starts the next
call. Gemini 3 also attaches a thought signature to each call (`extra_content`), requires it
back with the calls of the exchange in progress, and refuses `null` where it expects text.
The accumulator drops `extra_content`, so `ChunkFeed` keeps it by call, and
`OpenAIChatModel.replayable` builds the native turn without null fields and with each call's
`extra_content`. Native turns are not saved with a session; Gemini accepts earlier exchanges
without signatures, so a resumed conversation still works. Its thoughts, asked for through the
preset's `reasoningStyle`, arrive in the answer text between `<thought>` tags;
`OpenAIChatModel.TagSplitter` splits them off as the stream arrives, holding back a tail that
could begin a tag, and the stored answer and the replayed turn keep only the answer text.

The `chatgpt` api reaches the models of a ChatGPT plan through the backend the Codex CLI
uses (the provider's `url`, by default `https://chatgpt.com/backend-api/codex`), signed in the
way Codex signs in.
`ChatGPTAuth.begin` starts an OAuth authorization-code grant with PKCE and Codex's client id
at `auth.openai.com`, with a callback server on the loopback interface at port 1455 or 1457,
the two callbacks registered for that client. `FirstRun.signIn` opens the browser
(`Platform.openBrowser`) and prints the address. When the browser cannot reach the callback
(atc runs over SSH), the user pastes the address the browser ended on instead, and
`Login.paste` reads the code from it. The state must match either way. The tokens go to
`~/.atc/chatgpt-auth.json`, replaced atomically and readable only by its owner; the default
policy's locked `.atc` rule keeps the agent out of it. The ChatGPT account id comes from
the ID token's `https://api.openai.com/auth` claim, and the expiry from the access token's
`exp`. `ChatGPTModel` is the Responses adapter with an OkHttp interceptor that reads the file
for every request, so every atc process sees the newest tokens. The interceptor refreshes
tokens five minutes before they expire, and once more after a 401. A refresh token can be
used once, so a refresh holds a lock on `chatgpt-auth.json.lock` (created readable only by its
owner) and reads the saved tokens again once it has the lock: another process may have
refreshed while it waited. When the token endpoint still rejects a refresh token, the saved
tokens win if their refresh token differs; otherwise `SignInNeeded` tells the user to sign in
again from `/providers`. A failure of the sign-in step that is not an `IOException`, such as a
malformed token answer, fails the request as an `IOException` that names only the exception's
class, since its message may quote a token. The backend streams only, requires instructions
and rejects `max_output_tokens` and `temperature`. Its closing `response.completed` carries
an empty `output`, so `OpenAIResponsesModel.Accumulator` keeps the items of the
`response.output_item.done` events and uses them when the final response has none. The adapter streams one-shot calls too,
supplies instructions when a call has none, sends neither setting and sets
`prompt_cache_key` to the conversation id. The backend never returns the reasoning itself,
so a model without `reasoningSummary` asks for `auto` summaries, as Codex does, and shows
them as thinking. Its model list is `GET /models?client_version=`,
filtered to the models marked `list`, with their context windows and efforts. The backend
lists the models that Codex release may use, so `ChatGPTModel.ClientVersion` follows Codex
releases. The preset sends `originator: atc` and `session-id: ${ATC_SESSION}`. `ChatGPTSuite`
covers the flow against a local server.

The `claude-code` api reaches the models of a Claude plan through the `claude` CLI the user
installed and signed in to; ATC never reads its credentials. `ClaudeCli` starts `claude -p`
in the stream-json protocol of the Claude Agent SDK, in an empty temporary directory (deleted
when the process exits, or else when ATC exits), with
everything that would act or load context outside ATC turned off: `--tools=` (no built-in
tools; `WebSearch` alone when `webSearch` is on), `--setting-sources=` (no settings files,
hooks, plugins or skills), `--strict-mcp-config`, `--disable-slash-commands`,
`--no-session-persistence`, and `--permission-mode=dontAsk --permission-prompts=none` with
only ATC's MCP server allowed. The environment disables CLAUDE.md files, auto memory,
claude.ai connectors, auto-compaction (ATC compacts) and updates, and drops
`ANTHROPIC_API_KEY`, which would replace the subscription. The system prompt goes in the
`initialize` control request, since it exceeds Windows' command-line limit. The CLI asks the
API to omit thinking text by default; the hidden `--thinking-display=summarized` flag brings
back a summary, or the full text from models that give it (Haiku 4.5). The
`showThinkingSummaries` setting does not do this for newer models. ATC's tools are
an in-process MCP server (`"type": "sdk"`): the CLI sends JSON-RPC as `mcp_message` control
requests and every one, notifications included, needs a response.

The CLI runs its own agent loop, and `ClaudeCodeModel` keeps ATC's loop in charge. A
response that calls tools ends at its `message_stop` and is returned as a completion; the
CLI's `tools/call` requests, matched by `_meta.claudecode/toolUseId`, stay unanswered until
the next `complete` brings the agent's results. The CLI may call the first tool before later
`tool_use` blocks have streamed, and runs the calls one after another, so a result that
arrives first waits for its call. A completion carries a `NativeTurn` marker, so the next
request can tell whether its history extends what the session has seen: the results of the
pending calls, or user messages after a finished answer. Any other history (compaction, a
context cut, an interrupt, a model switch, a restored session), or a changed system prompt,
tool list or effort, ends the process and starts one that gets the history as a transcript.
A cancelled request stops its process. One-shot calls run in their own process without
tools. The model list comes from the `initialize` answer, and each model's context
window from `set_model` followed by `get_context_usage` (`rawMaxTokens`); none of these
makes a model request. A configured model without `contextWindow` learns its window the
same way when a session starts, until the CLI has answered once; the session is published
before that question, so an interrupt during it leaves a process that `close` or the next
session ends. On Windows, `WindowsExecutable` resolves `claude` from the PATH only, so a
`claude.exe` in the project cannot run in its place. `ClaudeCodeSuite` covers the
protocol against a scripted CLI.

Some compatible gateways end a stream without a `finish_reason`, with or without `[DONE]`.
The adapter returns an `Incomplete` completion containing received answer text and usage,
with no tool calls or native replay payload. The agent warns and requests a continuation;
after two such attempts in a turn it stops with a failed outcome. Normal provider output
limits retain their separate continuation budget. Reasoning is never used as answer text.
When a chunk contains both reasoning and answer text, reasoning is displayed first so the
answer stays together. Empty deltas and deltas after the finish chunk do not reach the UI.

## Conversation context and agent loop

The system prompt contains environment data, workflow instructions, capability rules, the
API source and configured permissions. Session grants are reported in tool results so they
do not change the prompt prefix. Repository-derived scalar values are JSON-quoted; larger
instruction and permission blocks are marked as data.

`CompletionPolicy` selects tool execution, continuation or completion. Calls from truncated
or blocked responses are not executed, and the assistant message says so even when it has
text. Output limits add `Msg.Continuation`; server-side pauses can resume directly. A tool
call the output limit cut gets its own continuation (`AgentMessages.truncatedToolCall`: the
call did not run, split it), since the plain one made models send the same call again, and
at most `Agent.MaxTruncatedCalls` retries. Resume and tool-budget rejection counts bound
repeated work.
The interactive tool budget may be extended by the user; non-interactive runs stop at it.

`TurnOutcome` records why the loop ended. `Finished` means the model produced a final
response, not that the user's overall objective was independently verified. Interruption,
provider refusal, exhausted limits and empty terminal responses have distinct outcomes.
`App` maps them to summary labels and process exit codes; uncaught execution errors remain
exceptions within the loop and are reported as `Failed` by the application.

`ModelRequest` runs at most one unfinished provider call per agent. The provider adapters
use SDK asynchronous streams, register their close callbacks before waiting for completion,
and accumulate events through the existing SDK accumulators. This permits cancellation
before HTTP headers arrive as well as during streaming. The caller blocks until the worker
ends or `ModelRequest.recheck()` finds the request's cancellation predicate true; the
threads that make it true (the SIGINT handler through `Agent.interrupt`, the key thread
through `Agent.submit`) call `recheck()`, which wakes the caller, closes the stream and
interrupts the worker, so nothing is polled during a call. A provider that does not stop prevents
another worker from accumulating behind it and produces a clear retry message. Request
completion or failure releases the active slot before returning to the caller, even if
the worker is still exiting. Cancellation retains the slot while the operation is running.
Stream sinks reject late output after the request ends. Provider clients are closed at application
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
boundaries. The latest exchange is retained. A cut rewrites the first message, which ends
every cached prompt prefix, so it goes down to three quarters of the budget
(`ContextManager.CutTarget`) and the next rounds fit without another one. An unavoidable overflow produces a warning
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
is estimated against the model's input allowance, using the same output reservation as
ordinary requests. If it cannot fit, the error suggests a larger model or `/new`.

`autoCompact` in `Agent`'s turn loop runs at the top of every round, after queued input is accepted and
before `ContextManager.prepare` fits the request: before the first request of a turn and
between tool rounds, so a long tool loop can be summarized while it runs. It never runs
between a tool request and its results, which would make the history invalid, and never
after the final answer, where a `-p` run would pay for a summary it never uses. It compares
calibrated
next-request usage with `contextWindow * autoCompactThreshold`, or with the input allowance
(the window less the output reserve) when that is smaller, since trimming keeps every request
within the allowance and a higher threshold would never be reached. This fraction defaults to
`0.8`, accepts `[0, 1]`, and uses zero to disable automatic compaction. It is a non-policy
setting merged with later-layer precedence, shown and changed by `/config`. When the exchange in
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
user message, inserting an assistant bridge after tool results when needed. When the user
interrupts the turn instead, queued text goes back to the prompt as a draft
(`Agent.takeQueuedInput`, `Tui.draft`) rather than starting another turn. The next model
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
offer and immediately quitting does not erase previous work; `/new` deletes it, so the next
start does not offer the conversation the user discarded. Conversation messages, pending
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

`Tui` implements `AgentUI` and owns the turn lifecycle, prose, the TODO panel, input and
the framing of pop-ups. It composes parts that live in their own files: `Screen` (writes
that track line boundaries, styles, width, live regions and the spinner; its monitor is the
TUI's lock), `StatusLine` (the footer and the window title), `ToolBlock` (one tool call's
block, its live output, its summary and the `/output` history), `ThinkingView` (the
reasoning window), `Dialogs` (what pop-ups show and read, through
`Menus` and `ListMenu`), `KeyReader` (the key thread during a turn), `PromptReader` (the
JLine line reader and its bindings) and `Alerts` (notifications and focus tracking).
`Format` holds the short number, duration and plural forms of status and summary lines.
`Ansi` removes terminal controls from external text before display. Keep model-visible
capture text unchanged; sanitize only at display boundaries. `TextSink` incrementally
handles UTF-8 and BOM-marked UTF-16 process output.

`Continuation` handles open brackets, strings and comments for `/run`. Shift+Enter and
backslash followed by Enter insert a newline. An empty line submits a code block; Ctrl-C
cancels block input. During a turn, a separate reader collects corrections and unsent
draft text and handles escape sequences, stopping on timeout or EOF. Menu reads pause that
reader.

Slash-command menus share one pattern, through `Tui`. A one-shot picker (`/model`, `/effort`,
`/perms revoke`) acts on the choice and closes. A menu the user comes back to after each
change (`/providers`, `/config`) is a `menuLoop`: its rows are rebuilt every time, so they show
the change, and its last row is Done. A menu opened from another is `chooseOrBack`, whose last
row is Back; a confirmation inside a command is such a sub-menu, with one action row. Carrying
out an action returns to the looping menu; Back, or an action that did not go through (a
refused change, a cancelled checkbox), returns to the menu one level up. Esc goes back one
level everywhere, and the footer says so (`Esc back`); the agent's questions say `Esc cancel`,
and permission requests, which Esc denies, say `Esc deny`.

Every menu is a `ListMenu`, drawn in a live region and read in raw mode; `MenuState` holds
what it shows apart from the terminal, and `MenuKey` decodes keys with `KeyReader`'s escape
parsing. A menu shows its title, then at most twelve rows, fewer when the screen is short, so
the whole menu stays shorter than the screen; the window scrolls with the cursor and says how
many rows are out of view above and below. A longer list is filtered by typing, every word
matching, with the filter and the number of matches on a line of its own; Backspace edits it
and Esc clears it before it leaves the menu. Ticks belong to items, so they survive a change
of filter, the ticked items open first, and the title counts them. PgUp, PgDn, Home and End
move by a page or to the ends. A menu opens on the value in use (`/model`, `/effort`,
`/classifiedmodel`) or, in a `menuLoop`, on the row last chosen. When it ends, one line says
what was chosen; leaving it leaves nothing. The footer shows its keys, or the menu's last row
when there is no footer. Menus read focus reports, and a resize redraws them at the new size.
Nothing inside a bracketed paste is taken as a key, and Enter counts only 300 ms after a menu
opens (`ListMenu.EnterDelayNanos`): a newline typed or pasted just before a permission request
must not choose its first row, Allow once.

While the main prompt holds a single word starting with `/`, `PromptReader` lists the matching
`SlashCommand.table` rows in JLine's `post` area under the buffer, with an exact name
preselected. It sets `post` in a `redisplay` override, leaves it to other users (completion
lists, history search) while they hold it, and skips it in the final display `doCleanup` draws.
↑/↓ move the selection, Tab fills in the name (and a space for commands with arguments), and
Enter replaces the word with the selected name before accepting. A line that ↑/↓ recalled from
history is not listed until it is edited, so the arrows keep browsing history. Answers, block
input and dumb terminals get no list. Enter runs the selection, so no alias may be a prefix
of another command's name.

During a turn, Enter submits a correction and unsent text is shown in the status line.
Bracketed pastes are collected without submitting individual lines, and the footer shows the
pasted text once, when the paste ends. The status line uses
JLine `Status`, updates on phase/input changes, and reserves a terminal row for the active
operation, elapsed time and model/mode/directory context. Spinner writes and status updates
share the TUI lock. The footer is reserved before the first content line, so adding it does
not scroll the banner away. Its activity indicator replaces a separate spinner when the
terminal supports a status line. Idle state shows a short model, mode and directory label;
menus and answer fields replace it with the applicable keyboard controls.
Resize signals update the footer even while a menu has paused the turn's key reader.
`Screen` measures the terminal once per resize, since the views ask for the width for every
line; the footer is resized to that measurement as well. JLine's line reader takes the resize signal while it reads,
so `Tui` measures again when a prompt or pop-up returns and redraws what depends on the size
if it changed; otherwise output after a resize at the prompt would keep the old width. A live
region redraws in place at the new width. Terminals that reflow on a narrower width (most
do, VS Code's included) rewrap the region's old rows, so a shrink can leave some of them
above the redrawn region.
Every footer update goes through `StatusLine.draw`, which flushes the terminal writer after
JLine's `Status.update`: JLine flushes the footer text but leaves the closing
synchronized-update sequence (`ESC[?2026l`) buffered, and a terminal that honours mode 2026
(xterm.js in VS Code, iTerm2, kitty, Ghostty, WezTerm) freezes rendering until it arrives.
Without that flush, a footer repainted from the input-poll clock stalled the window for up to
a poll interval per repaint (`TuiSuite` checks the closer is written).
ASCII mode also selects ASCII menu markers and control separators.
Streaming text is flushed at the end of each incoming update. Nested rendering helpers share
that flush instead of flushing every gutter and style fragment. Live previews compare their
rows with the previous view and immediately repaint only changed rows. `LiveRegion.redraw` cuts
every row to one terminal row, measured from column 0 with its gutter and tab stops, with
newlines shown as spaces: a row that wrapped would take two terminal rows and shift every later
redraw. `Screen.fit` stops scanning once the row is full, so a very long output line costs no
more than a short one. They use line erasure,
not erase-to-end-of-screen, so updating a preview cannot erase the footer. Footer work runs on
state changes and the existing input-loop clock; writing text does not trigger a footer redraw.
There is no frame-rate cap or deferred stream queue. `TuiSuite` checks preview growth,
replacement, shrinkage and clearing while preserving a footer outside the owned rows.
Process events wait while a pop-up is open, and during a turn until a tool block opens, where
they join its output, or the turn ends; written into streaming prose they would land mid-line.
Between turns they use `LineReader.printAbove` while the prompt reads, so they do not overwrite
the user's input.

`Notifier` sends the `notifications` alert when a turn ends, a permission request or
question opens, or the tool budget runs out. `Alerts` schedules it ten seconds ahead
(`QuestionDelayMillis`; `TurnDelayMillis`, thirty, for a finished turn) unless the terminal
is known to be focused, and drops it on focus coming back or on any key, answer or Ctrl-C: the turn's key reader, the prompt highlighter (a
changed buffer) and the end of a pop-up count as input. The terminal's focus reports
(`ESC[?1004h`) come through JLine's focus widgets at the prompt and through the key reader
during a turn. Reporting is on only while one of those raw-mode readers runs (`callback-init`
to `callback-finish` for the line reader, `KeyReader.start` to `KeyReader.stop`): between reads the
terminal is in line mode and its driver would echo a report as `^[[I`. Losing focus sends nothing early. Focus is
known only once the terminal has sent a report, since JLine assumes support for every `xterm*`
type and such a terminal may send none; until then only keys count. Menus read focus reports as well. There are no alerts for `-p` runs or dumb terminals.
The alert title is `atc · <directory>`. A turn's alert shows the start of its last prose
block as plain text (`Notifier.plainText`), or the outcome with the error or duration when
the turn did not finish normally (`Alerts.turnText`). Permission alerts name the request and
its details, and question alerts show the question.
`StatusLine.refreshTitle` sets the window title (OSC 0) from `busy` and the pop-up depth whenever
the status refreshes, on one line, writing only changes. The previous title is pushed on xterm's title
stack (`CSI 22;0t`) at start and popped (`CSI 23;0t`) by `close`; terminals without the
stack keep ATC's title until the shell sets its own.
Terminal alerts are OSC 9, 99 (kitty) or 777 sequences, chosen from `TERM` and
`TERM_PROGRAM` and wrapped for tmux passthrough; they are written as style text so they
leave the line tracking alone. System alerts start `osascript`, a Base64-encoded PowerShell
toast script or `notify-send` on a daemon thread, passing the text as arguments (after `--` for `notify-send`, so a body starting with `-` is
not an option) or quoted script data, and ring the bell if the command cannot start. Alert text is sanitized, put
on one line and capped at 200 characters.

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

In the compact view (the default on a terminal), a running tool block is one `LiveRegion`:
the title, up to eight rows of code and the last ten lines of output, both cut further so
the region stays shorter than the screen and can always be redrawn. When the call ends the
region becomes its summary: the title with the code's first line, the files changed and the
verdict, which names how much output it held or, for a failure, the first line of the
error (`ToolBlock.firstProblem`; a compiler heading gives its code or its message). A
pop-up, or any line written outside the block, freezes what the region shows and the
block continues below. Output that `parallel` tasks write while a pop-up is open is held, up to
its last million characters, and written when the pop-up closes; Ctrl-O takes the region down and writes the block out in full. The
expanded view and a plain terminal write every block in full, with the result panel.

`ToolHistory` retains up to 20 results within an eight-million-character budget. Each
result retains at most two million output characters plus bounded code and file previews;
live command output is capped separately at one million characters. A live view keeps its
text in a `TailBuffer`, which grows to half again its cap before cutting back, so small appends
do not move the whole text. `/output` displays
200-line windows without evaluating code again. Classified terminal-only output is never
added to the history, and `/new` clears it. These records are for inspection and are not
part of saved conversations.

Permission menus include **Tell the agent what to change**, followed by free-text input.
Empty or cancelled feedback returns to the menu. `PromptReader.readAnswer` consumes JLine's
preserved input-cancellation interrupt so the next menu can read normally.
Plain prompts accept exact `y`/`yes` or
`s`/`session` approvals, exact `n`/`no` denials, and treat other non-empty input as
`Decision.Revise`. A qualified answer starting with “yes” or “skip” must not become an
approval by prefix matching. EOF and empty plain replies deny the request.

Question menus always include a custom-answer option. Questions wrap with their continuation
lines under the question text, like permission details. For multiple selections, chosen
answers retain their display order and custom text is appended. User input is returned to
the host's `ask` call; only its terminal display is sanitized.

`MarkdownStream` incrementally renders supported Markdown and buffers tables until column
widths are known. `Highlight` uses the compiler's Scala scanner. A fenced Scala line is
highlighted with earlier lines as context: the last eight, or, while `Continuation.unclosed`
finds a comment or triple-quoted string open, up to 200 lines back (the compiler's colours
cannot tell what is open, since it resets them after the last coloured character). Layout calculations use
terminal cell widths. History is owner-only on POSIX systems and rejects final symlinks.
Non-interactive runs use a dumb UTF-8 terminal and do not prompt for permission unless
explicitly configured to auto-approve requests.

### Sessions, inspection and compaction

After each interactive turn the model predicts the next request and the prompt shows it as
ghost text (Tab or → accepts it; `"predictInput": false` disables it). When there is no useful
suggestion, the model is asked to return `[NO_PREDICTION]`. This marker and empty responses
are suppressed before reaching the prompt. A summary line
shows the turn's cost and how full the context window is. Turn summaries distinguish
finished, interrupted, blocked, failed and limit-reached responses; in scripted `-p` runs,
finished responses exit with `0`, interruptions with `130`, and other stopped outcomes with
`1`, and a finished response does not certify that every tool succeeded. Bracketed pastes
retain their newlines until Enter. The REPL starts in the background as soon as ATC is
ready for input, so the first Scala call usually finds it warm and ordinary conversation
never waits for a compiler.

| Command | Purpose |
|---|---|
| `/output` | List recent tool results |
| `/output last` or `/output 3` | Inspect retained output and file-change previews (bounded text diffs) |
| `/output 3 201` | Continue from a line in a long result |
| `/task` | Show the task goal, constraints, completed work and remaining steps |
| `/perms revoke` | Select a session grant to revoke (`/perms revoke 2`, `/perms revoke all`) |
| `/save [file]` | Save to a new file (never overwrites); default location `~/.atc/sessions/`, outside the project |
| `/resume [file]` | Resume the last session for this directory, or restore a saved file |
| `/ps`, `/kill [id|all]` | The processes the agent started with `spawn` |
| `/reset` | Fresh REPL, killing those processes |

Interactive terminal sessions save automatically on `/quit`, `exit` or Ctrl-D, under
`~/.atc/sessions/` per working directory (owner-only on POSIX); the next launch in the
same directory offers **Resume last session**. Exiting an empty session preserves the
previous save, scripted `-p` runs do not update it, and resuming does not replay tool calls
or restore permission grants. Output retention excludes classified terminal text.

For agent code, `replaceExact(path, expected, replacement)` checks that literal text occurs
exactly once before writing, `readRange(path, from, to)` and
`search(dir, pattern, glob, SearchOptions(...))` provide bounded reads and searches
(`SearchResult.limited` says whether a search was exhaustive), and `TaskNotes` keep task
state that survives context trimming.

`/compact [focus]` asks the current model for a summary of the older part of the
conversation and replaces that part with it (`/compact preserve debugging findings` guides
it). Task notes, pending notices, permissions and the live REPL remain; summaries are part
of saved sessions and the usage is listed under **context compaction** in `/cost`; Ctrl-C
cancels without replacing history. Manual and automatic compaction keep the most recent
complete exchanges verbatim, as many as fit within `compactKeepRatio` of the context window
(default `0.2`; `0` summarizes everything; valid values `0` through `1`), and summarize the
rest, keeping tool calls with their exchange. When everything fits there is nothing to
compact and no request is made; a summary that is not smaller is discarded; a transcript
larger than the model's input allowance is refused with a suggestion to use a larger model
or `/new`. Automatic compaction runs immediately before a request when its estimated size
reaches `autoCompactThreshold` times the model's `contextWindow` (default `0.8`; `0`
disables), before the first request of a turn and between tool rounds, never after a final
answer, and never without a configured window. A failed or not-smaller attempt is reported
and not retried until the conversation has grown by a tenth of the window; a new conversation
(`/new`) lifts that mark. Compaction is lossy; essential details belong in task notes or files.

## Testing and conventions

Tests use munit under `app/test/src/atc`. Extend the suite responsible for the behavior:

- `CapabilitySuite`, `ModeSuite`, `SandboxSuite`, `ReplSessionSuite`: compiler boundaries,
  capability requirements, REPL state and interruption.
- `PolicySuite`, `PermissionSuite`, `HostSuite`, `ClassifiedSuite`: permission rules and
  host effects, including classified values and local HTTP requests.
- `HostEditingSuite`: literal replacements, bounded reads and searches, file previews and classified exclusions.
- `ModelRequestSuite`, `ProviderCancellationSuite`: cancellation and HTTP client ownership across requests.
- `SessionStoreSuite`: portable conversation persistence, validation and file permissions.
- `ConfigSuite`, `LayerSuite`, `ModelSuite`, `GitIgnoreSuite`: configuration and lookup.
- `ToolOutputSuite`, `PromptsSuite`: tool-result bounding, hints and permission notes; the system prompt.
- `AgentCoreLoopSuite`, `AgentLoopSuite`, `CompletionPolicySuite`, `ContextManagerSuite`:
  loop decisions, transcript repair, context fitting and the real REPL integration.
- `TuiSuite`, `TextLayoutSuite`, `ToolHistorySuite`, `RenderSuite`, `InputPredictorSuite`, `DebugSuite`:
  terminal helpers, retained output, rendering, prediction and error reporting.
- `ReplInterruptionSuite`: cancellation recovery in an isolated compiler process.
- `ProcessesSuite`, `PlatformProcessSuite`, `TextFilesSuite`: process and platform behavior.
- `MainSuite`, `FirstRunSuite`, `commands.SlashCommandSuite`: command-line parsing, first-run setup and
  slash-command parsing.

`TestEnv` supplies temporary directories, scripted permissions and recording host ports.
`ReplAssertions` checks snippets. Prefer `ProcessFixture` over host shell commands for
portable process tests; reserve native commands for platform integration suites.
`tests/atc_test.sh` uses temporary files and stubbed downloads/Java to test the Unix wrapper
and checkout environment loading.

All Scala modules use explicit null checks where configured; Java APIs may require `.nn`.
Use `inline` selectively for small predicates, primitive conversions and wrappers where
expansion removes a closure. Ordinary parameters preserve evaluation order and evaluate
once; an `inline` parameter substitutes its expression at each use. `Debug.log` uses one
inline message expression behind the runtime debug flag, so disabled logging creates no
message closure. Keep larger methods and capability boundaries as ordinary methods.
See the [Scala 3 inline guide](https://docs.scala-lang.org/scala3/guides/macros/inline.html)
for parameter semantics and the distinction between `inline` and `transparent inline`.
Use `Platform` and `PlatformPath` for OS decisions and `ScalaSource` for generated Scala
literals. Keep model/provider escaping, shell quoting and terminal sanitization separate.
Scalafmt uses a 120-column configuration that preserves existing layout. `Interface.scala`
and `Runtime.scala` are excluded because the formatter cannot parse their capture-checking
syntax; format them by hand. Preserve tests for capability contracts when editing them.

## Wrappers, releases and CI

The Unix `atc` wrapper installs to `~/.local/bin` and keeps the JARs and the startup cache
in `~/.atc/jars`; `ATC_INSTALL_DIR` and `ATC_CACHE_DIR` override those locations. Release
downloads require SHA-256 digests for both JARs. Metadata uses jq when available and a
field-order-dependent fallback otherwise. A cache marker records `release-id|tag`; verified updates replace it
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

The same startup check then offers the wrapper itself (`offer_self_update`): at most once a
day (a `self-check` stamp beside the jars, touched before the check so a failed download or a
declined offer also waits a day), only when the running script is the installed one at
`INSTALL_PATH` (a checkout's copy is never overwritten), it stages GitHub's `atc` beside the
script with `stage_latest_self` (the download and `bash -n` check `atc self update` uses,
with the release check's timeouts), compares it with `cmp`, and on `y` moves it into place
with `install_staged_self`; bash keeps reading the running script from its old inode, so
the new wrapper takes effect at the next start.

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

CI builds distributions and runs application tests on Linux, macOS and Windows. Linux
checks formatting; Unix jobs run the Bash wrapper tests. Published release tags must match
`Versions.atc` (with an optional `v` prefix). The release job builds and uploads the two
JARs and Windows launchers after the platform jobs succeed.
