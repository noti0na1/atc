# ATC: development notes

This document explains how ATC is built and the reasoning behind its design. The
[README](../README.md) is the user guide; it covers installation, day-to-day use, and the
capability model at a high level. This document is for contributors. It covers building and
testing, architecture, the capability design, the sandbox, permissions, configuration
semantics, the terminal, and important implementation conventions.

## Table of Contents

1. [Building and running](#building-and-running)
2. [Architecture](#architecture)
3. [Capture checking in brief](#capture-checking-in-brief)
4. [The capability design](#the-capability-design)
5. [Defence in depth](#defence-in-depth)
6. [The sandbox](#the-sandbox)
7. [The permission model](#the-permission-model)
8. [Configuration semantics](#configuration-semantics)
9. [Models and providers](#models-and-providers)
10. [What the model sees: the context](#what-the-model-sees-the-context)
11. [The agent loop](#the-agent-loop)
12. [The terminal](#the-terminal)
13. [Testing](#testing)
14. [Conventions and gotchas](#conventions-and-gotchas)
15. [The wrapper script](#the-wrapper-script)
16. [Releases and CI](#releases-and-ci)

## Building and running

The repository ships Mill launchers for Unix (`./mill`) and Windows (`.\mill.bat`), so a
JDK 17+ is all you need. The Scala version is a pinned nightly (`Versions.scala` in
`build.mill`): capture checking and safe mode are experimental and move fast, so ATC tracks
one known-good build rather than a release.

```bash
./mill app.test                                   # all munit tests
./mill app.test.testOnly atc.ReplSessionSuite     # one suite
./mill app.test.testOnly atc.ReplSessionSuite -- '*timeout*'   # tests matching a glob
./mill app.compile                                # compile app (+ lib)
./mill __.checkFormat                             # scalafmt check; ./mill __.reformat fixes
./mill dist                                       # out/dist.dest/{atc,atc.ps1,atc.cmd,atc.jar,atc-lib.jar,version.txt}
./start.sh -C ~/some/project                      # sources .env, rebuilds dist if sources changed, runs the TUI
./start.sh -c cfg.json -p 'run: 1 + 1'            # one non-interactive turn (plain mode)
./mill -i app.run -C /some/project                # dev run without dist (-i keeps the terminal attached)
./mill app.test.runMain atc.Scratch file.scala [nosafe] [preamble.scala]   # run `// ---`-separated snippets in a sandbox
ATC_SKIP_BUILD=1 ./start.sh ...                   # skip the rebuild check
bash tests/atc_test.sh                            # tests of the `atc` wrapper script (bash 3.2+, no network, no Java)
```

The native PowerShell equivalents are:

```powershell
.\mill.bat app.test
.\mill.bat app.test.testOnly atc.ReplSessionSuite
.\mill.bat app.compile
.\mill.bat __.checkFormat
.\mill.bat dist
.\start.ps1 -C "$HOME\some\project"
$env:ATC_SKIP_BUILD = '1'; .\start.ps1 --version
```

`start.sh` is the Unix developer path: it rebuilds `out/dist.dest/` with `./mill dist` when a
source file is newer than the jar, sources a `.env` (`cp .env.example .env`; API keys and
the `ATC_*` variables below, without overriding what the shell already exported) and passes
its flags through to ATC. Without the script: `./mill dist`, then `out/dist.dest/atc`.
On Windows, `start.ps1` provides the same flow through the included native `mill.bat`; it
does not require Bash. `start.cmd` is the execution-policy compatibility entrypoint and,
like any batch file, is subject to `cmd.exe` argument parsing. The build temporarily runs at
the checkout root, while ATC itself retains the launch directory unless `-C` overrides it.

On Unix, `atc dev <checkout>` runs a local build through the *installed* wrapper: it copies
the checkout's `out/dist.dest/` jars into `~/.atc/jars/` in place of the release; see
[The wrapper script](#the-wrapper-script).

Environment variables: `ATC_DEBUG=1` (`atc.Debug`) prints stack traces and terminal/stream
diagnostics; `ATC_ASCII=1` draws the TUI with ASCII glyphs; `ATC_JAVA_OPTS` adds JVM flags
through the Unix wrapper and the two start launchers; `ATC_MODEL`, `ATC_CONFIG`, `ATC_CWD`
are `start.sh`/`start.ps1` shorthands for `-m`, `-c`,
`-C`; `ATC_ENV_FILE` selects another environment file and `ATC_SKIP_BUILD=1` skips the
staleness check. A provider with `"api": "echo"` is a key-less model (`run: <code>` in the
request becomes a `run_scala` call with that code, anything else is echoed back) for smoke
tests of the sandbox and TUI without network:

```bash
echo '{ "model": "echo", "providers": { "echo": { "api": "echo", "models": { "echo": {} } } } }' > /tmp/echo.json
./start.sh -c /tmp/echo.json -C /tmp/proj -p 'run: println("hi"); 21 * 2'
```

## Architecture

Two Mill modules:

| Module | What it is |
|--------|------------|
| `lib`  | The one API the model programs against: `atc.lib.Interface` plus the capability and data types (`FileSystem`, `Classified`, `Todo`, …), compiled with capture checking (`-language:experimental.captureChecking`, `-Wsafe-init`), every agent-visible definition `@assumeSafe`. No implementation lives here; the sandbox injection point (`atc.lib.Runtime`, holding `current`, the root capabilities and the derivations `fileSystem`/`readOnlyFileSystem`/`processes`/`network` the preamble builds the givens from, declared by the `Derivations` trait; all `@rejectSafe`) sits in its own file, outside the API source the model is shown. |
| `app`  | The agent program. `atc.host.Host` **implements `Interface` directly** (permission policy, file/process/network effects, questions, TODO list, LLM calls), and the REPL preamble binds that implementation as `api`, so a call in agent code is a plain method call on the host, with no marshalling layer to keep in sync. Also: sandbox and REPL management, LLM providers, terminal UI. |

```
lib/src/atc/lib/   Interface.scala: the agent-facing API (capabilities, data types, Interface)
                   Runtime.scala: the sandbox's injection point (@rejectSafe, not part of the API)
app/src/atc/
  (root)           LauncherEnvironment → Cli → Main → App; shared TextFiles/ScalaSource boundaries
  agent/           the small loop state machine; typed completion policy, transcript/context bookkeeping,
                   run_scala adapter and output rendering; system prompt and next-input prediction
  config/          the JSON config model: layers, merging, validation, keys, model catalog, templates
  host/            the Interface implementation: file/network effects; typed command grammar, Windows
                   executable resolution, and process lifecycle in separate components
  llm/             provider-neutral messages and ChatModel, plus the Anthropic, OpenAI and echo adapters
  perms/           the permission policy: rules and path patterns, scopes and grants, modes, gitignore
  platform/        the only OS/path-trait checks, portable paths, Win32 validation, slash-based globs
  sandbox/         the in-process REPL: preamble, diagnostic preflight, class-loader isolation, timeouts, interrupts
  ui/              the JLine terminal: streaming output, panels, pop-ups, Markdown and Scala colouring
app/test/src/atc/  munit suites, one per guarantee (TestEnv + ReplAssertions are the shared fixtures)
app/resources/atc/ the starting configs (config-template.json, project-template.json, keys-template.properties)
atc                Unix wrapper: installs, updates and runs release jars (tests/atc_test.sh)
mill / mill.bat    pinned Mill bootstrap launchers for Unix / Windows
start.sh           builds and runs a checkout on Unix
start.cmd/.ps1     builds and runs a checkout on Windows
capture-checking-bug/  a runnable repro of an upstream separate-compilation bug (see below)
```

### Data flow of one turn

`App` (wiring) → `Agent.turn` → `ChatModel.complete` → tool call `run_scala` →
`ReplSession.run(code)` → `CodeValidator` (fast diagnostic preflight) → compiler safe-mode
check → REPL eval with the
`Host` installed as the API implementation → `ExecutionResult` → `ToolOutput.renderForModel`
(bounded, hint-annotated) back into `Msg.ToolResults` → the model again, until it answers.

`ChatModel.complete` takes one `SystemPrompt(text)` built by `Prompts.system` from the
configuration, an injected `AgentEnvironment` and the mode, the configured permissions included;
permission grants do not change it (a mode switch restarts the sandbox, while an explicit
`/classifiedmodel` switch rebuilds the prefix once). A permission the user grants for the
session is reported in the tool result of the call that asked. Between explicit switches,
every request uses the same prefix plus what was appended, whatever caching the provider
offers (see [What the model sees](#what-the-model-sees-the-context)).
Completions stream into a `StreamSink` (text / notes / thinking) that the UI renders live.

History (`llm.Msg`) is provider-neutral, so `/model` can switch vendors mid-conversation;
each provider stashes a `NativeTurn` on the assistant message for exact replay when the
exact same model reference continues. Another model, even on the same protocol, receives
only the neutral text and tool calls; model-bound encrypted reasoning is never replayed to it.

### The lib ⇄ app boundary

`lib/src/atc/lib/Interface.scala` is the whole agent-visible surface: the capabilities
(`IOCap`, `UserIO`, `FileSystem`, `FileEntry`, `Exec`, `Network`), `Classified`, the data
types, and the `Interface` trait. The source of that file is bundled into the system prompt
(`Prompts.interfaceSource`, also what `/interface` prints), so wording there is prompt
wording. When you add an API method: declare it in `Interface.scala` (`@assumeSafe`),
implement it in `Host`, and remember the prompt. Plain signatures in `Host` may override the
capture-checked ones.

The REPL preamble (`ReplSession.preambleChunks(mode)`) binds `atc.lib.Runtime.current` as
`api`, `export`s it, and defines the top-level `given`s for the mode. It is loaded as
**several REPL rounds, one per given** (`init` loops over the chunks): each given then lands
in its own line-wrapper object, so a `Classified.map` that reads a file captures only the
`fs` wrapper instead of the always-full `user`/`io` ones. The givens must stay *top-level*
(not fields of `object api`): a capability field would force `object api` itself to be a
capability, and the `@untrackedCaptures` workaround stops uses being charged, which reopens
the default-argument leak described below.

Editing helpers in the API: `sed(path, regex, replacement)` is the targeted edit (Java regex
compiled with `(?m)` so `^`/`$` are per line; the replacement takes Java's `$1`/`${name}`
plus sed's `\1`/`\n`/`\t`, translated by `Host.sedReplacement`; it returns the match count
and throws when nothing matches, so a mistyped pattern cannot look like a successful edit;
literal text goes through the pure helpers `quote`/`quoteReplacement`
(`Pattern.quote`/`Matcher.quoteReplacement` underneath; temporary stand-ins, marked
`TODO(safe-mode)`, for `scala.util.matching.Regex.quote`/`quoteReplacement`, which safe mode
refuses until the stdlib is tagged)); `replaceLines(path, from, to, text)` /
`insertLines(path, before, text)` edit by the line numbers `cat` shows (`replaceLines`
returns the old text so a stale range is visible; `TextFiles` keeps the file's newline
style); `write`/`writeBytes` rewrite a whole file, `readBytes`/`writeBytes`
are the binary pair, and `move`/`copy` are composed of the checked read/write/delete
primitives (so a classified file cannot be moved or copied out). Viewing: `cat(path)` /
`cat(path, from, to)` print `cat -n`-numbered lines through the `println` path (capped at
`Host.CatMaxLines` with a note naming the next window; lines cut at `Host.CatMaxLineChars`),
and are what the prompt tells the agent to look at files with, so that it reads windows of
big files and can quote line numbers; `read` stays raw for code. Listings
(`ls`/`walk`/`find`/`GrepMatch.file`) show paths relative to the working directory when
inside it (`Host.display`), absolute outside, and turn Windows separators into `/`;
`find`/`grepRecursive` globs match the file name, or the
path relative to `dir` when the glob contains `/` or `**` (`PathGlob`,
gitignore-flavoured: `**` spans directories, a leading `**` + `/` also matches none).
**Commands.** `exec(command)` uses the small grammar in `CommandLine.parsePipeline`: quoted
words, `|` between stages, `< f`, `> f`, `>> f`, and `2>&1`. There is no shell, so `&&`,
`;`, `||`, `&`, `2>`, backticks, and `$(` are rejected. `ProcessBuilder.startPipeline`
runs the stages with real pipes. Each stage is checked independently against the allowed
patterns and deny list, and the `requestExec` hint identifies missing permissions. Input
and output redirections pass through the normal file checks and reject classified paths.
The pipeline uses pipefail-style exit codes and labels standard error by stage.

Arguments in `args: Seq[String]` are appended verbatim, while
`ExecOptions(workingDir, timeoutMs, stdin)` controls the remaining behavior. `exec` returns
non-zero exits normally, whereas `execOutput` throws; timeout errors include the output
captured so far. Both `exec` and `spawn` use `Host.prepare` for parsing, permission checks,
and `ProcessBuilder` construction, then `Processes.ManagedProcess` for execution. A managed
process owns the pipeline stages, bounded output buffers fed by drain threads, an optional
live view, and an exit watcher. A JVM-wide registry lets the shutdown hook kill any process
still running.

`exec` starts the process, waits up to the timeout, and returns `result()`. `spawn` returns
a `ProcessImpl`, an `atc.lib.Process` capability that captures `ex` and therefore cannot
enter `Classified.map`. Its standard input remains open for `send`; `read` and `readErr`
consume output, `readUntil(regex, ms)` waits without consuming on failure, and `waitFor`
and `kill` manage its lifetime. The host permits at most `Host.MaxProcesses` live processes
and assigns IDs (`p1`, `p2`, …) that are never reused. `runningProcesses` removes completed
entries, and `App` calls `host.killProcesses()` when the REPL session ends. The user sees
start, input, and exit events inside the tool block and can use `/ps` or `/kill [id|all]`.
Spawned output is not streamed automatically; the agent reads it explicitly, and the
result panel displays what it read. `Agent.hints` turns `Cannot run program` failures into
PATH and no-shell guidance. A single parsed pipeline is capped at 16 stages.

On Windows, a bare executable is resolved from `PATH` (not the working directory), using
`PATHEXT`; write `.\tool` to opt into a repository-local executable. This permits normal
`.exe`, `.cmd`, and `.bat` entry points such as `npm` and `mill.bat` without pretending that
`dir`, `copy`, or a PowerShell cmdlet is an executable. A backslash is a path separator, not
a space escape, so quote an argument containing spaces. Batch files inherently use the
Windows command processor after their stage is authorized; strict JDK quoting and ATC's
`%`/`!` checks keep their arguments from becoming extra commands. Other shell built-ins require an explicit
`cmd.exe`/PowerShell command and therefore a correspondingly powerful permission grant.
External programs choose their own newline and encoding conventions. `TextSink` defaults to
UTF-8 and recognizes a leading UTF-8/UTF-16 BOM; tests of native tools must still allow CRLF
and other platform-specific output rather than assuming Unix `\n`.

**HTTP.** `httpGet`/`httpPost` throw on status >= 400 with the status and a body prefix
(`Host.checked`), `httpRequest` is raw, and response bodies are capped at 8 MiB. Any request
carrying a classified body or header returns `Classified[...]`; construction, transport,
status and body failures after unwrapping stay inside it, so a peer cannot reflect a secret
back into plain data. JSON: `atc.lib.Json` (the enum and its companion live in `Interface.scala`
so the agent sees the API; parser/renderer in `lib/.../JsonCodec.scala`, not bundled).
`ExecOptions` and `Todo` are plain data types, so their default arguments are fine: the no-
defaults rule is about capability-taking methods.

## Capture checking in brief

ATC's design relies on an experimental Scala 3 feature. This section introduces the
concepts needed to understand the rest of the document.
[Capture checking](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/index.html)
adds a second dimension to the type system: in addition to tracking *what* a value is, the
compiler tracks *which capabilities it can reach*. A **capability** is a value tracked in
this way, and its type is a subtype of `caps.Capability`. ATC's `FileSystem`, `Exec`,
`Network`, `UserIO`, and `IOCap` types all qualify through `caps.ExclusiveCapability`. The
feature is based on a small formal calculus with a soundness proof; relevant papers are
listed under [References](#references).

**Capture sets.** Every type carries a *capture set*, written in `^{...}`, naming the
capabilities that values of the type may use:

```scala
val a: FileSystem^{fs}        // may use exactly fs
val b: FileSystem^{fs, net}   // may use fs and net
val c: FileSystem^            // ^ abbreviates ^{cap}: may use anything
```

A type without `^` captures nothing and is therefore *pure*. The compiler propagates
capture sets through calls, closures, and fields without widening them implicitly. A value
typed `^{fs}`, for example, has been proved unable to access the network.

**Subcapturing.** Capture sets are ordered by inclusion, and that order lifts to subtyping:
`T^{fs}` is a subtype of `T^{fs, net}`. A value that uses fewer capabilities can stand in
for one that may use more, but not the reverse. Passing a `T^{fs, net}` where a `T^{fs}` is
expected is therefore a type error. This rule underpins both read-only views and the mode
hierarchy.

**Functions capture what their body uses.** A function value's capture set is the union of
the capabilities its body refers to. `A -> B` is a pure function (empty set); `A => B` is
sugar for `A ->{cap} B`, one that may use anything; and `A ->{fs} B` may use `fs` and nothing
else. This is the type used by `Classified.map`:

```scala
def map[B](op: T ->{any.rd} B): Classified[B]
```

`op` may capture only *read-only* capabilities (`any.rd`). A body that tries to print,
write, call `exec`, or call `httpGet` therefore does not compile because each operation
requires a full capability. The confidential value can be transformed, but it cannot be
routed out through one of those channels.

**Capabilities cannot escape their scope.** A capability introduced for a bounded region
cannot be captured by anything that outlives that region. Storing it in a longer-lived
binding, returning it, or closing over it in an escaping function causes a compile error.
The calculus models this with *boxes*, and the error says that the capability "cannot be
included in outer capture set." This rule allows a `request*` block to receive a wider
capability safely:

```scala
val stolen = requestFiles("/tmp", Access.Write, "cache") { // lends a full FileSystem^
  write("/tmp/x", "ok")     // fine: the capability is used inside the block
  summon[FileSystem^]       // error: returning it would let it escape the block
}
```

**Safe mode** ([reference](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/safe.html))
is a second experimental layer that ATC enables alongside capture checking. Capture
checking is sound only when *every* capability is tracked; a library that returned an
untracked capability—for example, a `println` that secretly held `System.out`—would create
a hole. Safe mode closes this gap by rejecting definitions that are not explicitly marked
safe. The agent-visible API is `@assumeSafe`, while the sandbox injection point is
`@rejectSafe`, so agent code cannot name it. Untagged standard-library objects are
unavailable, which is why the API provides pure alternatives for a few standard methods.
Safe mode comes from [TACIT](https://github.com/lampepfl/tacit), on which ATC builds.

**Mutability.** ATC also uses the nightly compiler's
[mutable-capability model](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/mutability.html),
which *reinterprets the bare type as the read-only view* (`FileSystem` means
`FileSystem^{any.rd}`, not the pure empty set) and marks the mutating operations `update`,
callable only through the full `^`. The next section explains this refinement and how ATC
combines these pieces into its [capability design](#the-capability-design).

### References

- **The feature:** the Scala 3 nightly documentation. The project tracks a pinned compiler
  build ([capture checking](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/index.html),
  [safe mode](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/safe.html),
  [mutability](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/mutability.html)).
- **The formalized calculus:** *Capturing Types*, by Boruch-Gruszecki, Brachthäuser, Lee,
  Lhoták and Odersky (ACM TOPLAS, 2023), which defines the capture calculus (CC<:, with boxes
  for the escape checking above) and proves it sound.
- **The agent application:** *Securing Agents with Tracked Capabilities* (TACIT, CAIS '26),
  which introduces safe mode and the capability-typed sandbox that ATC repackages.

## The capability design

This section expands on the README's
[overview](../README.md#the-idea-capabilities-instead-of-ambient-authority) with the details
required by the implementation. If the `^{...}` capture-set notation is unfamiliar, read
[Capture checking in brief](#capture-checking-in-brief) first.

**Capabilities cannot be forged.** The capability classes have `private[atc]` constructors
(agent code is compiled into the empty package), the only instances are the ones the REPL
preamble binds. `atc.lib.Runtime` contains `current`, `rootIO`, `rootUser`, `install`, and
the `fileSystem`, `readOnlyFileSystem`, `processes`, and `network` derivations. It is
`@rejectSafe`, so agent code cannot even name it under safe mode. The regex validator also
recognises the common direct spelling so it can return a shorter error before compilation;
the compiler check is authoritative.

**Mutability / read-only tracking** follows the nightly's `mutability.md`: the mode-tracked
capabilities (`IOCap`, `UserIO`, `FileSystem`, `FileEntry`) extend
`atc.lib.Cap = caps.Stateful, caps.ExclusiveCapability`. A bare type is the read-only view
(`^{any.rd}`); `^`/`^{io}` is full. The write side (`FileEntry.write/append/delete/mkdir/
writeClassified`) are `update def`s, callable only through a full capture set. `x.rd` names
the read-only view of `x`: `val ro: FileSystem^{fs.rd} = fs` is a file system that provably
cannot write, for helpers, or for reading files inside `Classified.map` where the full `fs`
may not be captured (what the API's `readOnlyFileSystem` used to hand out; it now lives in
`Derivations`, for the read-only mode's preamble only).

**Two roots.** `IOCap` derives `fs`/`ex`/`net` and is read-only in local and read-only mode
(so no writable `fs`, `Exec` or `Network` can be derived from it); `UserIO` is *always* full
(`given user: UserIO^`) and is what `println`/`print`/`printf`/`ask`/`setTodos`/`markTodo`,
normal-model `chat(String)` and every `request*` take, so reporting and permission prompts work in every
mode while those effects stay out of `Classified.map`. `todos` takes a read-only `UserIO`.
`Exec`/`Network` are plain `ExclusiveCapability` (no read-only view) derived only from a
full `IOCap^`. Why `UserIO` is not derived from `IOCap`: a mode withdraws the machine
(`IOCap` becomes read-only) and leaves the conversation intact, so the agent can always say
what it would have done. The local-mode network error is the model of this: `network` is
`def network(using io: IOCap^): Network^{io}`, there is no full `IOCap` to derive one
from, so `httpGet` simply has no given to resolve:

```
No given instance of type atc.lib.Network was found for parameter x$2 of method httpGet
```

**`Classified.map`** is `T ->{any.rd} B`: the callback may capture *read-only* capabilities
only. Every untrusted outward channel needs a full capability (`println`/`ask`/`chat`/`setTodos` →
`UserIO^`; `write`/`append` → `FileSystem^`; `exec` → `Exec`; `httpGet` → `Network`;
`request*` → `UserIO^`), so none of them compile inside `map`; reading files does compile
where `fs` is itself read-only (read-only mode). Do **not** loosen any of those to a
read-only capability without re-running the leak audit (`CapabilitySuite`).
`chat(message: String)` in particular used to be the hole that made a bare `{any.rd}`
unsound.

`classifiedChat(String)` is the deliberate trusted primitive: its configured model is
assumed to run in an isolated classified environment with no outward connection or side
effects, so the API treats it as pure and admits it inside `Classified.map`.
`classifiedChat(Classified[String])` is the label-preserving
`message.map(classifiedChat)` wrapper. This is a trusted-computing-base/configuration
assumption, not something the host can prove about an arbitrary configured endpoint.

**The API has no default arguments on capability-taking methods**; use overloads instead.
A defaulted parameter makes a `def` wrapper eta-expand to a pure function and slip into
`Classified.map` (a real, demonstrated exfiltration:
`def h(s) = exec("echo", List(s)).stdout; classify(x).map(h)` used to run the command).
`exec`/`execOutput`/`httpGet`/`httpPost`/`httpRequest`/`httpPostClassified`/`ask`/
`grepRecursive`/`requestFiles`/`requestExec`/`requestNetwork` are therefore telescoping
overloads, and `CapabilitySuite` has regression guards for the def-wrapper case. A related
upstream bug (separate compilation losing an object's capability status) has a runnable
repro in `capture-checking-bug/`.

**`request*` blocks** open a child permission scope that only widens, lend the block a
capability carrying that scope's id (`FileSystemImpl(scope, host)`; `opaque type ScopeId =
Long`, base scope `ScopeId.Base`), and close the scope when the block exits. Capture
checking keeps the lent capability inside the block; the scope id keeps a value that
somehow outlived it (none should) from being honoured by the host, which resolves
permissions per call. `requestFiles` lends a file system exactly as capable as the one in
scope (full in local/full mode, read-only in read-only mode), so the same call site compiles
in every mode.

## Defence in depth

Scala compiler safe mode, together with capture checking, is the authoritative static safety
check for agent code. The runtime policy, class-loader boundary and deny rules support that
compiler-enforced model at execution time. `CodeValidator` appears earlier in the pipeline
only to improve feedback; it is not an independent safety layer.

1. **The policy at run time.** Every host method checks the permission `Policy` for the
   path, command, or host, including the scope ID of the capability used for the call. A
   process handle returned by `spawn` also carries its originating scope. Consequently,
   `runningProcesses` returns only processes visible from the caller's scope chain, and all
   operations on a handle are refused after its scope closes. A process started inside a
   `requestExec` block is killed when that block closes (`killProcessesInScope`), so a
   one-time grant cannot leave behind a live but inaccessible process. `Policy.mode`
   enforces the three modes again by downgrading writes to reads and refusing `exec` or
   network access, so the type check is not the only safeguard.
2. **Preflight feedback (not a safety layer).** `sandbox/CodeValidator.scala` is a
   deliberately small, fast lexical preflight before compilation. It recognises common invalid forms—direct ambient APIs,
   known escape-hatch spellings and evaluator-hostile catches—and returns focused guidance
   without paying for a compiler round. It does not parse or type-check Scala, is not
   exhaustive, and may have both false positives and false negatives. Accepted code is not
   thereby safe: safe mode must still compile and approve it. Keep the implementation to
   cheap regexes and linear scans; do not turn it into a second Scala parser or security
   checker. Add a rule only when its early diagnostic is useful, and cover that feedback in
   `CodeValidatorSuite`. If `safeMode` is disabled, the authoritative compiler check is
   deliberately absent; stricter validator feedback does not restore the same guarantee.
3. **Class-loader isolation** (`sandbox/Sandbox.scala`): the REPL's class loader has
   `SandboxLoader` as parent, which delegates only `scala.*` and `atc.lib.*` to the app
   loader and everything else to the platform loader, so agent code cannot see `atc.host`,
   the LLM clients, JLine or the compiler, and the sandbox classpath holds no third-party
   library. `getResource` hides `*.class` so `-Xrepl-interrupt-instrumentation:true`
   instruments only REPL-defined classes (otherwise the REPL would re-define
   `atc.lib.Interface` and lose the installed host). `Runtime`'s bootstrap slot is
   process-global; the process-wide evaluation lock re-selects a session's own host before
   lazy preamble initialization and every evaluation, so concurrent session objects cannot
   mix their capabilities.
4. **Deny lists at the effect.** `commandDenied`/`hostDenied` are consulted where the
   effect happens (so a session grant, an open scope or `--approve-all` cannot pass them),
   and `refuseDenied` throws out of `requestExec`/`requestNet` before the prompter is
   called, with a message telling the model the refusal is final.

## The sandbox

**Modes** (`perms/Mode.scala`, `perms/Policy.scala`): `ReadOnly`, `Local`, `Full`. The
preamble hands out only that mode's givens (read-only `io`+`fs` / full `fs`+`ex` / full
`io`+`fs`+`ex`+`net`, plus the always-full `user`), so the wrong effect does not type-check;
`Policy` (its `@volatile var mode`) also enforces it at run time. Switching mode (`/mode`,
Shift-Tab, `--mode`, config `"mode"`) sets `policy.mode` and starts a fresh REPL
(`App.restartSession`); the conversation is kept and the agent gets a `[sandbox notice]` on
its next turn saying its definitions are gone. `/new` (`App.newSession`) goes further: it
drops the REPL, `agent.clear()`, `host.clearTodos()`, `policy.resetSession()` (session
grants and open scopes; rules, deny lists and mode stay), calls `System.gc()` (the old
compiler and class loader are most of the process's memory) and starts a fresh REPL.

**Evaluation, timeouts, interrupts** (`sandbox/ReplSession.scala`): evaluation runs on a
worker thread. `ExecutionClock.paused` excludes both the time spent waiting for the user
(permission prompts, questions) and the time a command runs (`HostOutput.whileCommandRuns`,
which `App` maps to the same clock pause), plus nested `chat` model calls that own a provider
request timeout (pauses nest), so an external wait does not trip the snippet's shorter
timeout. Interrupts and timeouts raise the REPL stop flag through `OpenReplDriver`, a
subclass of the compiler's `ReplDriver`. The driver also installs `CappedRendering`, which
lives in **package `dotty.tools.repl`** because `Rendering` is `private[repl]`.
`skipPoisonedWrapper()` advances the wrapper index so a half-initialised class name is never
reused. `clock.reset()` happens at the top of every
`run`. Whether agent code threw an uncaught exception comes from the renderer
(`CappedRendering.renderError` sets `threw`; the REPL prints the trace as ordinary output),
not from scanning the output. `close()` marks the session closed (later `run`s fail with a
clear message) and interrupts a running evaluation; the compiler and class loader are
reclaimed by the GC once nothing refers to the session. A runtime error in agent code does
not fail the REPL: the session reports it to the model as an error with host stack frames
trimmed (`ExecutionResult.trimStackFrames`) and hints appended for common capture-checking
stumbles (`Agent.hints`: read-only-capture / missing-`Exec`/`Network` errors map to "switch
mode" advice).

**Output capture.** The REPL driver's output (echoes, diagnostics) and `System.out/err`
during evaluation go into a bounded capture (`BoundedOutputStream`, a truncation marker
past the cap). The agent's own `println`s reach the same capture through
`HostOutput.print` → `ReplSession.printStream` (so they interleave with REPL output in
order) and the terminal through `Tui.agentPrint`, which remembers them to subtract from the
result panel by exact substring (`Tui.withoutPrinted`); that relies on `ReplSession` not
trimming leading whitespace of captured output. Echoed values are capped
(`SandboxConfig.maxEchoChars`, without ever splitting a surrogate pair); printed output is
bounded only by the capture.

**Safe-mode quirks** the agent hits, documented in the system prompt (`agent/Prompts.scala`):
top-level `val`s of capturing types need explicit types; `Option.foreach/map` need pure
functions; no top-level `var` and no `scala.collection.mutable` (a *local* `var` accumulating
into an immutable collection is fine, and `StringBuilder` works); `Thread.sleep`/
`System.nanoTime` are unavailable (use `java.time`); stdlib objects not tagged `@assumedSafe`
are refused (`scala.util.matching.Regex.quote`/`quoteReplacement`: the API's pure `quote`/
`quoteReplacement` stand in until then, marked `TODO(safe-mode)`). Writing helpers need
`(using fs: FileSystem^)` (the bare `FileSystem` is read-only). `Prompts.modeSection(mode)`
adds a mode paragraph.

## The permission model

`perms/`: a `Policy` = configured `FileRule`s + session grants + a tree of scopes + the
`denyCommands`/`denyHosts` lists.

**File rules.** Patterns are gitignore-flavoured (`perms/PathPattern.scala`): no `/` →
matches a path component anywhere; relative with `/` → against the working directory (or
the project folder for a project rule, `LayeredRule.base`); absolute and `~/…`. A rule
applies to the matched path and its whole subtree. Effective access is the *minimum* over
every matching rule (unmatched → none), classified if any rule says so, locked if any does;
so a sub-folder inherits its parent's permission and can only be made stricter
(`build/generated: write` under `build: read` still yields `read`).

Configuration uses `/` separators on every OS. A Windows absolute path should be written as
`"C:/Users/alice/project"`; native backslashes are JSON escapes and must otherwise be doubled
(`"C:\\Users\\alice\\project"`). Path-taking API calls accept native Windows input too.

**Every path returned by the host is canonical, with Windows separators rendered as `/`**
(`Host.canonical` for named paths, `PlatformPath.portable` for API text, and `visibleEntries`
for listings). Returned strings should be passed as values; code generators must still
quote legal filename characters such as quotes or a literal Unix backslash. A symlink
is listed and evaluated as its target, so an entry returned by `children` or `walk` receives
the same checks as that path would receive
if addressed directly. This also means that a link in a readable directory to a classified
file remains classified when listed; `HostSuite` contains the regression test. Dangling
links are resolved as well because writing through one creates its target, and the policy
must evaluate that target. Every directory entry is canonicalized because a Windows
junction/reparse point need not report itself through `Files.isSymbolicLink`; link-like
directories are listed but never traversed.

**Classified paths.** Content is only observable as `Classified[String]`; a classified
directory's structure is classified too (listing needs `childrenClassified`/
`walkClassified`, returning `Classified[List[String]]`; `walk`/`grepRecursive`/`find` do not
descend into it). A plain `write` to a classified path is refused (use `writeClassified`),
and `writeClassified` to a non-classified path is also refused because it would declassify
the value. Sinks do not reveal whether a classified computation failed: a pure `map` can
fail conditionally on the secret, so exposing the failure bit would create a per-bit oracle.
`writeClassified` checks the permission and target classification *before* inspecting the
value, ensuring that a denied or non-classified target fails identically in either case. If
the computation failed, it writes no content but still creates the target, preventing file
existence from revealing the failure bit, and reports the failure only to the user. A
failed secret HTTP header cannot simply be omitted because the resulting response, such as
a 401 instead of a 200, could reveal the failure. All overloads carrying classified input
therefore keep both failure and response in a `Classified` result. Rendering a classified
value is guarded too: an agent-defined `toString` or `Throwable.getMessage` cannot throw a
secret-bearing exception back into the REPL result.

The information-flow claim is **termination-insensitive**. A pure callback can still vary
its running time, resource consumption or termination with a secret, and a timeout can make
that difference observable. The prompt forbids using such side channels; the in-process
sandbox cannot make arbitrary Scala computations constant-time or total.

**Commands and hosts.** `commands` are patterns over the whole command line: `*` is a
wildcard, a pattern without `*` matches by word prefix (`"git status"` allows `git status
--short`; `"ls"` allows `ls -la` but not `lsblk`; `"git diff"` allows `git diff HEAD` but
not `git difftool`). A command also needs read access to the directory it runs in (the
working directory by default), which must not be classified; that check goes through the
`FileSystem` capability, so a `requestFiles` block covers it. A pre-approved command runs
with the user's privileges and is not subject to the file rules (`git diff --no-index a b`
reads any two files), hence the advice to pre-approve subcommands rather than `git *`.
`hosts` are globs on host names; only `http`/`https`; redirects are not followed, so a host
the agent is sent on to has to be listed itself. `denyCommands`/`denyHosts` use the same
syntax and win over everything (see [Defence in depth](#defence-in-depth)); they are listed
in `/perms` and in the system prompt.

**Prompts and scopes.** Pop-ups go through `PermissionPrompter`/`HostUi.askUser`;
`App.whileUserDecides` pauses the execution clock while the user answers. A grant is
*once* (the block's scope) or *for the session* (`Policy` session grants, forgotten by
`/new`); `locked` blocks both. Session grants from a prompt bypass the config layers' caps
(the human decides).

**Visibility, not permission:** `perms/GitIgnore.scala`. With config `respectGitignore`
(default true) `Host.visibleEntries` drops `.git` and every `.gitignore`-matched path (the
enclosing repository's files and nested ones, with `!` negations, `**`, directory-only
`dir/` and anchoring as git reads them), so `ls`/`walk`/`find`/`grepRecursive`/`children*`
skip them while reads and writes by name still work. The policy decides access first, then
gitignore hides; nested `.gitignore` files hide more the deeper you go.

## Configuration semantics

The user-facing summary is in the [README](../README.md#configuration); this is the exact
behaviour (`config/Layers.scala`, `Config.load`/`combine`; tests in `LayerSuite`).

**Three layers** are applied in order: global (`~/.atc/config.json`), project (the nearest
`.atc/config.json` at or above the working directory), and explicit (`-c file`).
`Config.projectRoot` finds the project by walking upward; a `.atc` directory containing
either `config.json` or `keys.properties` establishes a project.

**The program grants no permission implicitly and writes no file without asking.** On an
interactive run, `App.setup` offers to create `config-template.json` and
`keys-template.properties` when `~/.atc/config.json` is missing (`Config.ensureGlobal`). If
the user declines, ATC loads the template as an in-memory `Origin.Global` layer with
`path = None`; `/config` displays this as `(bundled)`. Non-interactive `-p` runs never ask:
setup offers are skipped and permission requests fail closed unless the caller chose
`--approve-all`; this is reported as a scripted-run limitation, not as a user denial.
If no configuration grants the working directory and it has no project configuration,
`App.setup` offers to create `project-template.json` and `.atc/.gitignore` through
`Config.initProject`, the same operation used by `--init`, and then reloads. After creating
the global configuration, ATC exits with `App.Exit(0)` so the user can add keys. Once
created, configuration files are changed only by `/model` and `/classifiedmodel`.

The templates protect resources but grant no access. The global template classifies common
credential paths (`.ssh`, `.gnupg`, `.env`, `.env.*`, `.netrc`, `.npmrc`, `.pypirc`,
`.docker`, `.kube`, `.aws`, `.azure`, `.gcloud`,
`*.pem`, `id_rsa`, `id_ed25519`), sets `.atc` to `none` and `locked` (the pattern has no
`/`, so it covers `~/.atc` and any project's `.atc` alike: the agent can read neither the
config nor the keys, and no prompt can open them), denies `rm -rf *` and `sudo *`, and
grants no files, commands, or hosts. The project template opens the project tree, marks
`./.git` read-only and `./secrets` classified, allows read-only Git commands, denies
`git push*` and `git reset --hard*`, and permits documentation hosts. Git commands are
governed by `commands`, not by file rules. The host list includes official documentation
and paper hosts, but not package registries or code-hosting services, because `httpPost`
may send data to any permitted host.

**Project rules are read against the project folder**: running atc in `repo/src/main`
picks up `repo/.atc/config.json`, and `"./build"` there always means `repo/build`. Should
the search reach the home directory, `~/.atc/config.json` stays the granting layer rather
than becoming a project one (a path named twice keeps its first role). The asymmetry: a
relative pattern in the *global* config still means "relative to the working directory"
(that config is tied to no project), so `{ "path": ".", "access": "write" }` in
`~/.atc/config.json` opens whatever directory atc is started in, and only that, while the
same rule in a project config always opens the whole project.

**Non-policy settings** (`model`, `classifiedModel`, `providers`, `instructions`,
`predictInput`) merge in layer order, later wins; providers merge per provider and then per
model alias, so a project config can add a model to a provider the global config defined
without repeating its `api`, `url` or `key`, and a redefined alias replaces that model entry
outright.
This makes repository configuration authoritative inside its checkout; users should review
project model endpoints, standing command/host grants, and instructions before running it.

**Policy settings come from the granting layers** (global and `-c`) and are then narrowed
by the project layer:

* **`files`**: `Policy.configPerm = min(ceiling over every matching rule, max over the
  matching rules that may grant p)`. A project rule carries `grantsWithin =
  Some(projectRoot)`, so it grants only inside the folder holding its `.atc` while its
  ceiling applies wherever it matches: a project config opens its own tree, never reaches
  outside it (`{ "path": "~/.ssh", "access": "read" }` in one grants nothing), and never
  exceeds a granting layer's limit. `classified` and `locked` only ever restrict, so they
  apply from any layer and no layer can take them off.
* **`commands` / `hosts`**: the plain union of every layer's list (a project may
  pre-approve the commands and hosts its work needs; the deny lists are the backstop).
* **`denyCommands` / `denyHosts`**: union; any layer can add, none can drop.
* **Scalars** (`mode`, `safeMode`, `respectGitignore`, `maxToolCalls`,
  `maxToolOutputChars`, `executionTimeoutMs`): min / or-towards-on, but only for keys the
  layer actually defines (`ConfigLayer.defines`), which is why leaving a key out and setting
  it to its default are different things. `safeMode` is a latch: on unless a granting layer
  sets it false; a narrowing layer can only switch it on.

Narrowing is unconditional and order-independent (every step is a minimum, an "or" or a
union): `-c` outranks the project layer for the model but cannot undo its narrowing.
Session grants from a prompt bypass the caps; `locked` still blocks them. `.gitignore`
comes last and is not a permission at all.

**`/model` and `/classifiedmodel` persist**: when cwd has its own `.atc/config.json` the
switch is written there (`App.remember` → `Config.setTopLevel`, a layout-preserving
top-level-key edit over the `config/ObjectText.scala` scanner, `null` for `off`); a parent
project's config is never written, and nothing is written without one in cwd; a `-c` file
that sets the same key still wins.

## Models and providers

`config/ModelCatalog.scala`: the config has `providers` (an `api` wire protocol + `url`/
`key`/`keyEnv` + models by alias); `ModelConfig` holds only per-model settings plus `name`
(the provider's model id, defaulting to the alias). `ModelCatalog.from(config)` flattens
them into `ModelSpec`s (provider, alias, api, modelId, baseUrl, resolved key, settings) in
provider-then-alias order; `find` takes a bare alias, or `provider/alias` when two providers
share an alias (the bare alias is then refused with both candidates named), and `label`
prints the shortest unambiguous name. A model's `name` may contain a slash
(`"anthropic/claude-sonnet-4.5"` on OpenRouter); only the alias may not. A provider may list
no models (an endpoint ready for a later layer to fill in). One vendor reachable through
two protocols is two providers, since the protocol belongs to the endpoint.

`ChatModel.create(spec)` dispatches on `spec.api`; the adapters extend `SpecModel`
(`llm/Providers.scala`: names, `cfg`, `webSearch`, `contextWindow` from the spec) and read
`spec.settings` for everything else; the two OpenAI-shaped ones share `OpenAIShapedModel`
(client, `thinking` switch, guessed lowest effort and its fallback). Per adapter:

* `anthropic`: Messages API (official Java SDK). `webSearch: true` adds the server-side
  `web_search` tool (`web_search_20260209`; `"webSearchVersion": "20250305"` for older
  models). Adaptive thinking is on unless `"thinking": false`; `reasoning` maps to
  `output_config.effort` (`low|medium|high|xhigh|max`).
* `openai-responses`: Responses API. `webSearch: true` adds the built-in `web_search` tool;
  `reasoning` maps to `reasoning.effort`; `"reasoningSummary": "auto"` asks for streamed
  reasoning summaries (shown as thinking; DeepSeek streams its reasoning without it).
* `openai`: Chat Completions. `webSearch: true` sets `web_search_options` (only
  search-enabled models accept it).
* For both OpenAI-shaped adapters `"thinking": true|false` sends the vendor switch
  `thinking: {"type": "enabled"|"disabled"}` (DeepSeek/GLM/Kimi/MiniMax;
  `Providers.thinkingSwitch`, an extra body property on every call); unset for OpenAI
  itself, which rejects the parameter.
* `echo`: `EchoModel`, key-less, for tests and smoke runs; it honours `contextWindow` so
  the context display can be demoed.

`ChatModel.simple(system, prompt, thinking)` is the one-shot call (normal `chat`, trusted
`classifiedChat`, and next-input prediction). With `thinking = false` it disables Anthropic thinking
and sends OpenAI the lowest `reasoning_effort` the model family takes
(`Providers.lowestEffort`: `none` ≥ 5.1, `minimal` GPT-5, `low` o-series or any model the
config gives an effort; nothing for models not known to reason), or `disabled` when the
vendor thinking switch is configured; a `BadRequestException` on that guess sets
`effortRejected` only when its parameter/message specifically identifies reasoning effort
(`OpenAIShapedModel.withEffortFallback`), and the request is then repeated plainly, once per
process. Unrelated 400 responses are not retried. `thinking = true` applies the configured
thinking/effort, the same as `complete`.

**API keys**: a provider keeps `key`/`keyEnv` naming a `${VAR}`; the *values* come from
`.atc/keys.properties` (`config/Keys.scala`, read with `java.util.Properties`, so
`NAME=value`, `NAME: value`, `#`/`!` comments, `\` escapes and line continuations).
`KeyBindings.get` tries the project file, then `~/.atc/keys.properties`, then
`System.getenv`; an empty value is not a binding, so the lookup falls through. New key
files on POSIX file systems are created with mode `0600`; ATC warns when loading one that is
readable by the group or other users. Windows files instead inherit the parent directory's
NTFS ACL, which ATC currently neither rewrites nor audits. The normal global location is
`%USERPROFILE%\.atc` (`$HOME\.atc` in PowerShell); inspect its ACL with `icacls` on a shared
machine.
`Config.resolveApiKey(provider, bindings)` feeds `ModelCatalog.from(config, keys)`.
`ModelSpec.toString` masks the key and `/config` prints variable names only, never values.

**Context window.** `ModelConfig.contextWindow: Option[Tokens]` (`config/Tokens.scala`, an
opaque `Int` whose upickle reader takes a number or `"256k"`/`"1m"`; decimal multipliers) →
`ChatModel.contextWindow: Option[Int]`. Before every model call `Turn.fitHistoryToContext`
drops whole exchanges from the front of `agent.history` (`Agent.fitToContext`, cuts only at
`Msg.User` boundaries, keeps the last user message) until `estimateTokens` (chars/4 ×
`tokenCalibration`, the ratio observed prompt tokens / estimate from the previous
completion; `TokenUsage.input` is the whole prompt for every provider, Anthropic's cache
reads/writes included) fits after reserving `max(window/8, configured max output tokens)`;
the first kept user message gets `Agent.contextCutNotice(total dropped)` prepended and the
UI warns. The estimate uses a native assistant payload only for the exact model that can
replay it and resets its tokenizer calibration when `/model` switches.
`Agent.contextUsage` (the same calibrated estimate of the next request, plus the window)
is what the turn summary line (`Tui.TurnStats`, `Tui.contextUsage`) and `/cost` show as
`context 45.2k/200k (23%)`. TODO: compaction (summarise instead of cut).

**Usage accounting.** `ChatModel.simple` returns a `Reply(text, usage)`; every model call
is recorded in `Agent.recordUsage(purpose, usage)` (a synchronized per-purpose ledger:
`Agent.Turns`/`Chat`/`ClassifiedChat`/`Prediction`; `agent.usage` is the sum), which is
what `/cost` prints.

## What the model sees: the context

Every request to the agent model is assembled from the same four parts, in this order.
The order matters for prompt caching (the provider caches a prefix), so the parts that
change least often come first and the history last. A **turn** is one user
message and everything until the model's final answer; within it, every **round** (one
model call) re-sends the whole context so far, plus the tool results of the previous round.

```
┌ system prompt (stable between explicit mode/classified-model switches) ───┐  Prompts.system(...).text
│ identity: "a coding agent with tracked capabilities … acts only by         │  changes only with cwd, config,
│   writing Scala and running it with run_scala"                             │  mode or classified-model switch
│ Environment: working directory, OS, REPL flags, whether a classified       │
│   model is configured (never which), the gitignore note                    │
│ How to work: orient first (find and read AGENTS.md/CLAUDE.md/README/…,     │
│   learn the build and test commands from them and the build files),        │
│   explore → sed/write → verify with exec → println, request* on            │
│   "Access denied", session grants are reported in results, do not          │
│   retry capability compile errors, small snippets, todos/ask, never        │
│   end on a plan                                                            │
│ Sandbox mode paragraph (Prompts.modeSection): the givens of the mode       │
│   and what does not compile in it                                          │
│ Rules of the sandbox: what is forbidden, read-only vs full views,          │
│   top-level val/var quirks, Option/effects, fatal throwables, Classified   │
│ API reference: the full source of lib/…/Interface.scala                    │  Prompts.interfaceSource
│ Configured instructions: config "instructions", if any                     │
│ Current permissions: mode, file rules (classified-only patterns folded     │  policy.configSummary:
│   into one line), commands, hosts, the always-refused lists; never the     │  no session grants, so
│   session grants                                                           │  it never changes
├ tools ─────────────────────────────────────────────────────────────────────┤  Prompts.ToolName, toolDescription,
│ run_scala(code: String): the only tool                                     │  toolParameters (JSON schema)
├ history (agent.history: List[Msg]), turn by turn, append-only ─────────────┤
│ turn 1                                                                     │
│   User("fix the failing test")                                             │  round 1 sends everything above + this
│   Assistant("Let me look at it.", [run_scala(code₁)], native)              │  the model answered with a call
│   ToolResults([ToolResult(id₁, rendered result₁, isError)])                │  round 2 sends all of it again + these
│   Assistant("", [run_scala(code₂)], native)                                │  code₂ asked with requestExec …
│   ToolResults([ToolResult(id₂, rendered result₂ +                          │  … and the user allowed it for
│       "[permissions: the user allowed … for the session …]", isError)])    │  the session: said here, once
│   Assistant("Fixed: the assertion compared …", [], native)                 │  no call: final answer, turn over
│ turn 2                                                                     │  the REPL was reset in between
│   User("[sandbox notice] The Scala REPL was restarted …                    │  pending notes, then the request,
│         [user ran code] … ```scala … ``` Result: …                         │  in one user message
│         now add a regression test for it")                                 │
│   Assistant(…, [run_scala(code₃)], native) · ToolResults([…]) · …          │
│   Assistant("Added …", [], native)                                         │
│ turn 3 …                                                                   │  and so on; /clear or /new empties it
│ (once the window is full, the oldest turns are dropped, cut at a User, and │  fitHistoryToContext
│  the first kept User starts with "[context notice] The N oldest …")        │
└────────────────────────────────────────────────────────────────────────────┘
```

**The system prompt** is a single `SystemPrompt(text)`. Its contents include
`Policy.configSummary` without session grants, so permission decisions never invalidate the
prefix. A mode switch restarts the sandbox and rebuilds it; `/classifiedmodel` also changes
the one availability line and therefore the prefix. Between those explicit switches each
request consists of the same cacheable prefix followed by new messages. OpenAI-compatible
APIs can use automatic prefix caching, while Anthropic uses explicit breakpoints on the
system block and final history message. Providers without caching lose nothing. Keeping a
live permissions block in the prompt would invalidate this prefix after every grant, which
an earlier implementation did.

The prompt embeds the complete source of `Interface.scala`, which is also displayed by
`/interface`. Its docstrings are therefore the authoritative descriptions of individual
methods. The surrounding prompt explains workflow, permission requests, and REPL or
safe-mode conventions, then directs the model to those docstrings for API details. A mode
paragraph is also included, which is why changing modes restarts the REPL and rebuilds the
prompt. The prompt states whether a classified model is configured, so the model knows
whether `classifiedChat` is available, but never identifies that model.
`Agent.systemPrompt` is recomputed before every round; ATC does not cache it internally, but
the result remains identical while mode and classified-model availability stay unchanged.

**Prompt decisions travel in the history.** The model cannot see the pop-ups, so without
feedback it takes an "allow once" for a standing grant and a later "no" for a revocation
(both observed live). `Policy.decide` therefore logs every decision
(`decisionCount`/`decisionsSince`: once, session, denied, with the phrase it was about);
`Agent.runScala` reads what was logged during a tool call and `renderForModel` appends
`[permissions: the user allowed commands npm * once (this call only; a later call must ask
again); the user allowed read on '/x' for the rest of this session (no request needed from
now on); the user denied …]` after the (possibly cut) result, so the model learns exactly
where it happened, keeps seeing it in later rounds, and the already-sent messages never
change. `/run` does the same for decisions made while the user's own code ran. A session
grant later dropped by the context cut or forgotten by `/clear` costs at most a needless
`request*`, which the policy answers without a prompt since the grant still holds; `/new`
resets grants and history together.

**The history** (`llm.Msg`, provider-neutral; `Agent.history`) holds exactly what was
said and done, never the terminal's rendering. `Conversation` owns its mutation and
role-repair invariants; `AgentMessages` owns the control text inserted into it:

* `Msg.User(text)`: the user's request. *Pending notes* are prepended to it, each on its
  own paragraph, so the transcript never has two user messages in a row: `[sandbox notice]`
  (the REPL was restarted by `/reset` or a mode switch: definitions are gone),
  `[user ran code]` (what the user ran with `/run`, as a fenced block, and its rendered
  result), and, on the first kept message after a context cut, `[context notice] The N
  oldest messages … were dropped`. Notes are *prepended* to the next user message
  rather than inserted as messages of their own, again so that nothing already sent changes.
* `Msg.Assistant(text, toolCalls, native)`: the model's prose and its `run_scala` calls
  (`ToolCall(id, name, arguments)`, the code as JSON), plus the provider's `NativeTurn` for
  exact replay when the same model reference continues (Anthropic content blocks including
  server-side web-search results, Responses output items); another model gets the neutral
  text and calls. An interrupted turn ends with `Assistant("[interrupted by
  user]")` so the history stays well-formed.
* `Msg.Continuation(text)`: an internal user-role bridge after an output-limit stop. It asks
  the provider to continue the truncated assistant response without pretending to be a new
  real user turn; prediction and context-cut boundaries therefore ignore it.
* `Msg.ToolResults(results)`: one `ToolResult(callId, output, isError)` per call, where
  `output` is `ToolOutput.renderForModel(result, config.maxToolOutputChars)`:
  `ExecutionResult.render` (the captured REPL output with host stack frames trimmed, then
  `ERROR: <message>` for a compile/validation/timeout error, or `(no output)` /
  `(failed, no output)`), a `Hint:` line when `ToolOutput` recognises the error (read-only
  capture / missing `Exec` or `Network` → "switch mode" advice), the whole thing cut in
  the middle with `… [N characters omitted] …` beyond `maxToolOutputChars`, then the
  `[permissions: …]` note when a prompt was answered during the call (uncut, last);
  `isError` is `!result.success`. The budget and cancellation cases are results too ("Tool budget of N
  calls per turn exhausted; answer the user now.", "Cancelled by the user before
  execution.", "Missing 'code' argument."), as is an unknown tool name.

What a tool result contains is therefore: the agent's own `println`s (`agentText`, so a
classified value is `Classified(***)` here while the human saw the content), anything the
REPL echoed (top-level `val`s and the last expression, capped at `maxEchoChars`),
diagnostics and traces; and `ask()` answers come back as the return value inside the
program, not as messages. What it does *not* contain: the live output of a long `exec`
(that is display only; the `ProcessResult` carries it), the contents of `.atc` (locked by
the starting policy), and any key (`/config` and the prompt name variables only). The
model's own reasoning is in the context only as part of a provider's native turn (Anthropic
thinking blocks, Responses reasoning items) when the exact same model reference continues;
the neutral history, and so any other model, has only the text and the calls.

**Fitting the window.** Before every model call, when the model has a `contextWindow`,
`ContextManager.prepare` estimates the request (`fixedTokens` = system prompt + tool
schema, plus its estimate of every message, chars/4 scaled by the calibration
from the provider's last prompt count) after reserving the larger of `window/8` and the
adapter's configured maximum output tokens, and drops whole
exchanges from the front (`ContextManager.fitToContext`: cuts only at `Msg.User` boundaries so no
tool result loses its call, and always keeps the last user message); `contextDropped`
accumulates so the notice states the total. The same estimate is `Agent.contextUsage`,
shown after each turn and by `/cost`. If the fixed prompt or latest exchange cannot fit,
the turn proceeds for advisory/custom windows but emits an actionable warning.

**The other model calls have their own, smaller contexts.** `chat(message)` from agent
code is a capability-requiring one-shot call to the untrusted normal model.
`classifiedChat(String)` is exposed as an assumed-pure call to the isolated classified
model, with a one-line system prompt ("a trusted assistant handling confidential data");
the `Classified[String]` overload maps it without removing the label. The next-input prediction sends
`InputPredictor.System` and a rendered transcript of the last `Exchanges` user/agent text
pairs (tool calls and results left out, each message cut to `MessageChars`), never to the
classified model. Nested chats pause the snippet clock while their provider-level timeout
is in force. All of them are recorded under their own purpose in `/cost`.

## The agent loop

`Agent.turn` binds a `ScalaToolRunner`, then the core loop runs *rounds*. Each
`Turn.round` asks the model and follows the typed `CompletionPolicy` decision: run tools,
resume, or finish. Per-turn counters enforce `Agent.Max*` and `config.maxToolCalls`:

- Provider adapters map wire-specific stop strings once into `CompletionStop`; the loop
  branches on that typed value through `CompletionPolicy`.
- Tool calls run one at a time through `ToolRunner`; `ScalaToolRunner` owns `run_scala`
  argument decoding, REPL execution, timing and result rendering. Results are appended
  before the model is asked again.
- If the provider returns a resumable stop after a server-side tool such as web search
  (Anthropic `pause_turn`), the model is asked to resume, up to `MaxResumes` times.
- Output-limit stops (`length`, `max_tokens`, `max_output_tokens`) append a
  `Msg.Continuation` and resume. Tool calls accompanying a truncated or safety-blocked
  response are treated as partial and never executed. Empty terminal responses get a
  visible assistant marker so later provider history stays valid.
- The tool budget (`maxToolCalls`, 200 by default) is a checkpoint. When it is exhausted, the
  UI is asked (`AgentUI.confirmMoreToolCalls`; the TUI shows a yes/no pop-up, `-p` runs and
  test doubles decline) whether the turn may go on for another budget; if not, the model gets
  an error result and the turn stops after `MaxBudgetRejections` repeated requests.
- Any other response is the final answer. There is no longer a "nudge": a heuristic over the
  reply's prose misread too many closings; the system prompt says that ending without a tool
  call means the turn is finished, and the user can always say "go on."

`cancelled` is polled before probing each stream event and before every tool call; an
interrupted turn ends with an `[interrupted by user]` assistant message when role repair is
needed. Safety/refusal stop reasons are shown to the user.

**Pending notes.** Things the model must hear at the start of its next turn (`[sandbox
notice]` after a REPL restart, `[user ran code]` after `/run`) are queued in an ordered
list and prepended to the next user message, so the transcript never holds two user
messages in a row; `clear()` drops them.

**Next-input prediction** (`agent/InputPredictor.scala`, config `predictInput`, default
on): after each interactive turn `App` calls `predictor.start()`, which asks
`agent.model.simple` for the likely next request on one coalescing daemon worker and hands it to
`Tui.suggest`; the TUI draws it as faint ghost text through a `DefaultHighlighter` subclass
(display only: JLine positions the cursor from the buffer), and Tab / → accept it
(`atc-accept-suggestion-*` widgets fall back to the previous binding). A generation counter
drops stale guesses; rapid starts retain only the newest waiting job and interrupt the
current SDK call best-effort, so a client that ignores interruption still cannot create
unbounded workers. Model/session changes and `/run` invalidate old state. Prediction
transcripts are JSON-quoted data, and output is reduced to one control-free line.
`Tui.suggest` redraws via `callWidget(REDISPLAY)`, which JLine only honours while reading.
Plain mode and `-p` runs never predict; the classified model is never used.

**`/run [code]`** (`App.runCode`) lets the user run Scala in the same `ReplSession`:
rendered with `tui.toolStart(code, "/run")`/`toolEnd` inside `beginTurn`/`endTurn` (so
Ctrl-C interrupts it and Ctrl-O works); `agent.noteUserRan(code, result)` queues the note
for the model. It is not counted as a tool call.

## The terminal

`ui/Tui.scala` implements `AgentUI` directly. Every content kind has one shape, so a glance
tells them apart:

```
> your request

● assistant prose                       bullet + indent, Markdown rendered as it streams

● run_scala                             tool block (magenta)
  │ code the agent runs
  ├ output                              the program's own println output, live
  │ hello
  │ $ ./mill test                       a command (exec) that keeps running: its
  │ compiling ...                         output as it comes
  ├ result   (or  ├ error)              what the REPL added: echoed values, diagnostics
  │ val x: Int = 1
  └ ok 34 ms (or └ failed 34 ms)

  ▸ TODO  ✓ done  ▶ in progress  ○ pending      redrawn once per snippet
  ⚠ Permission request …  /  ? question         pop-ups (list/checkbox menus)
```

Before display, untrusted text—including model prose and reasoning, tool code and output,
and paths embedded in prompts—passes through `Ansi.sanitize`, which strips C0/C1 controls
and ESC. All output then goes through a single `write` method that tracks the final two
characters (`tail`) so gutters can
be inserted into arbitrarily chunked streams; `LiveRegion` redraws in place, and the two
things that use it own their state as inner components (`thinking` = the reasoning window /
full stream, `liveOutput` = folding of long program output, budgeted in wrapped terminal
*rows* via the pure `Tui.place` so long or newline-free output cannot fill the screen;
`toggleExpanded` detaches and re-renders both); the result panel bounds itself the same
way, by fitting each shown line to one row. The limits are the constants in `Tui`'s
companion (`MaxPanelLines`, `FoldAfterRows`, `FoldTail`, `ThinkingWindow`). A raw-mode key
thread runs only during a turn (Ctrl-O toggles expanded view; other keys become type-ahead
for the next prompt) and is paused (`keys.withPaused`) around JLine pop-ups. Styles are
hand-rolled SGR codes (`ui/Ansi.scala`, shared with the Markdown renderer and the
highlighter); JLine's `toAnsi` rewrites box glyphs into DEC escapes, which is also why the
continuation prompt is ASCII; the glyph set (Unicode or ASCII, `ATC_ASCII=1`) is
`ui/Glyphs.scala`. `Markdown.scala` (streaming renderer: headings, lists, quotes, rules,
emphasis, code spans, fenced code blocks coloured when the fence says `scala`, pipe tables
drawn once complete) and `Highlight.scala` (the compiler's `SyntaxHighlighting`) are pure
and unit-tested. Plain mode (no real terminal) disables colours, menus, folding and live
regions, and pop-ups fall back to a plain `answer>` line.

**Live program output.** `Tui.agentPrint(agentText, userText)` shows the agent's prints
as they happen (classified values are shown to the user marked `[classified]`, the model
sees `Classified(***)`) and remembers `agentText` to subtract from the result panel.
**Live command output**: `Processes.run(pb, name, timeout, live: Option[LiveOutput])`
drains stdout/stderr through a `TextSink` (incremental UTF-8 decoder, `host/TextSink.scala`)
into a `LiveGate` that holds the text back (a tail of `LiveBacklogChars`) until the command
has run for `Processes.LiveAfterMs`, then calls `begin()` once and `output(text)` per chunk
from the drain threads; `Host.exec` maps these to `HostOutput.commandRunning(line)`/
`commandOutput(text)` (default no-ops on the trait; `TestEnv` records them), and `App`
forwards to `Tui.commandRunning`/`commandOutput`, which print `$ line` and the chunks into
the same `├ output` section as the prints but *not* into the subtraction buffer (the tool
result does not carry them). Quick commands show nothing. Compiler safe mode rejects direct
`System.out/err` access (the validator reports the common spelling earlier), so only
`println` and this are live; the REPL's
captured stream (echoes, diagnostics, `printStackTrace()`) stays in the result panel.

**Input.** `Tui.readLine` is a JLine `LineReader` with: a `Completer` for slash commands
(`Tui.completions`, words typed so far → values for the last word; `App` fills it from
`SlashCommand`, the pure table of names, aliases and `/help` text in
`app/src/atc/SlashCommand.scala`, and the `/model`/`/classifiedmodel`/`/mode` argument
values; `SlashCommand.parse` resolves a typed line and `App.dispatch` matches over the enum,
so a command without an action does not compile); Shift-Tab → `/mode` on an empty prompt
(`atc-cycle-mode`); the ghost-text suggestion; and **multi-line input** in two halves. Keys
(the `atc-enter`/`atc-newline` widgets, bound in every mode): Enter (CR and LF) goes through
`atc-enter`, which turns a trailing `\` into a newline (typed by hand, or how an iTerm2/VS
Code set up by Claude Code sends Shift+Enter; VS Code sends `\` CR LF, so the LF is peeked
and dropped via `LineReaderImpl.peekCharacter`) and otherwise calls `ACCEPT_LINE`; CSI-u
Shift+Enter (`ESC[13;2u`), xterm modifyOtherKeys (`ESC[27;2;13~`) and Alt+Enter (`ESC CR`)
insert a newline directly. Parsing (`ui/Continuation.scala`, pure, tested in `TuiSuite`,
applied by a JLine `Parser`): on accept it throws `EOFError` (with the bracket depth,
`INDENTATION` 2) so JLine inserts an indented newline instead of accepting, when the last
line is non-empty and either `Tui.readBlock` is active (a bare `/run`: until an empty line)
or a `/run`/`/scala` line has unclosed brackets/strings/comments per
`Continuation.unclosed` (a small Scala lexer: brackets, strings with escapes and triple
quotes, char literals, line and nested block comments). Enter on an empty line always
submits (the escape hatch); `readLine` strips the trailing blank line. The secondary prompt
is `%P | `.

**Turn summary.** `endTurn(TurnStats)` prints `● worked for 3 s · 2 tool calls · 1.2k
tokens · context 45.2k/200k (23%)`; `Tui.count` formats short numbers, `Tui.contextUsage`
the context part (`context ~45.2k` without a window).

## Testing

All tests are munit, under `app/test/src/atc/`; compiler-heavy timings vary by platform. They
share `TestEnv.scala` (temp root, scripted permission decisions, recording host output, live
command output, `newSession`, `activate()`) and `ReplAssertions.scala` (`assertOk`/
`assertFails` for REPL snippets). `AgentCoreLoopSuite` uses an in-memory `ToolRunner` and
needs no filesystem or compiler; `AgentLoopSuite` adds the real REPL boundary with
`ScriptedModel`/`RecordingUI`, still without a network. `AgentUI`, `HostOutput`/`HostLlm`/`HostUi`, `ChatModel`
and `StreamSink` all have test doubles; changing those traits means updating them (the
two `HostOutput` live-output methods have no-op defaults for that reason).

Keep semantic tests independent of the host shell: use `ProcessFixture` for pipeline and
process behavior, and reserve platform commands for `PlatformProcessSuite`. Text-file
format guarantees belong in `TextFilesSuite`; paths and OS decisions use `PlatformPath` /
`Platform`, while generated Scala literals use `ScalaSource`. Returned API paths use `/`
on every OS. Source fixtures use LF, while assertions about native process output must
account for the platform newline. Windows runs test classes serially because parallel Mill
workers can duplicate the compiler-heavy first suite.

Suite layout, by what each one guarantees (put a new test where its *guarantee* lives, not
where the code lives):

* **`CapabilitySuite`**: the capability type system (read-only vs full views, `update`
  methods, derivation from `IOCap`, `UserIO` vs `IOCap`, escapes from `request*`, forging,
  and the `Classified.map` capture contract incl. the def-wrapper/default-argument
  regression guards).
* **`ModeSuite`**: the read-only/local/full matrix (`onlyIn(modes, code)` asserts a snippet
  compiles in exactly those modes) plus the policy's runtime enforcement and the config/CLI
  plumbing.
* **`SandboxSuite`**: the sandbox itself (session, host wiring, persistence, loader
  isolation, compiler boundary and validator diagnostics).
* **`ReplSessionSuite`**: REPL mechanics (language coverage, errors, timeouts, interrupts,
  output capture and caps, REPL command allow-list).
* **`ClassifiedSuite`**: `Classified` semantics against the host directly.
* **`PermissionSuite`** / **`PolicySuite`** / **`HostSuite`**: the permission model, the
  policy algebra, the host's path canonicalisation, exec (incl. live output) and network.
* **`LayerSuite`**, **`ConfigSuite`**, **`ModelSuite`**, **`GitIgnoreSuite`**,
  **`CodeValidatorSuite`**: configuration layering, the config model, the model catalog and
  adapters, gitignore matching, the validator's early diagnostics.
* **`AgentLoopSuite`**, **`AgentSuite`**, **`InputPredictorSuite`**: the loop, the system
  prompt and rendering for the model, the context cut, pending notes, usage, prediction.
* **`TuiSuite`**, **`RenderSuite`**, **`SlashCommandSuite`**: the terminal's pure helpers
  (row placement, print subtraction, number formatting, the continuation rules), the
  Markdown/highlighting renderers, the slash-command table.

The bootstrap host slot is process-global, but `ReplSession` re-selects its own host under
the evaluation lock before lazy preamble initialization and every run. `TestEnv.activate`
remains useful for direct setup code, but live sessions no longer depend on callers racing
to select the slot. `Scratch` (`./mill app.test.runMain atc.Scratch file.scala`) runs
`// ---`-separated snippets in a sandbox and prints the results, handy for trying agent
code by hand.

## Conventions and gotchas

* `-Yexplicit-nulls` everywhere, including agent code: Java results are `T | Null`, use
  `.nn`; regex match groups are nullable. `-Wsafe-init` in `lib`.
* `inline` is a keyword; don't name methods that.
* Formatting: scalafmt via Mill (`__.reformat` / `__.checkFormat`), style-preserving config
  (keeps line breaks, docstrings, trailing commas; 120 columns). `lib/.../Interface.scala`
  is excluded (scalafmt cannot parse capture-checking syntax), so format it by hand.
* The REPL echoes top-level values; echoes are capped (`SandboxConfig.maxEchoChars`) and the
  TUI subtracts the agent's own prints from the panel by exact substring, which relies on
  `ReplSession` not trimming leading whitespace of captured output.
* One REPL session per conversation: definitions persist across turns until `/reset`,
  `/new` or a mode switch, and the model is told when they are gone.
* Driving the TUI programmatically (from a script or pty): if the launching shell put
  SIGINT to `SIG_IGN` (background jobs of non-interactive shells), the JVM cannot register a
  handler and Ctrl-C does nothing; reset signal dispositions in the child. jline-prompt menus
  use application-cursor mode (`ESC O B` for down). The first interactive run asks the
  "write a project config?" question on stdin, so a piped script's first line answers it.
* `CLAUDE.md` (git-ignored, for the coding agent working on this repository) carries the
  same architecture notes in compressed form; keep the two in step when you change a
  mechanism described here.

## The wrapper script

`atc` (repo root, bash 3.2+) is the Unix user-facing installer/launcher, modelled on TACIT's
`tacit`: `atc setup` (install to `~/.local/bin`, PATH snippet in the shell profile, Java 17+
check, download), `atc update`, `atc self update|uninstall`, and anything else (`atc`,
`atc -C dir`, `atc run ...`) execs `java -Datc.lib.classpath=atc-lib.jar -jar atc.jar` from
`~/.atc/jars/` (beside the global config; uninstall validates the cache root and removes only
ATC-owned artifacts, never the surrounding `config.json`/`keys.properties`;
`ATC_CACHE_DIR`/`ATC_INSTALL_DIR` override, used by the tests). It fetches `releases/latest`
from the GitHub API (`GITHUB_TOKEN` raises the
rate limit; `jq` when available, a grep fallback otherwise), needs the assets `atc.jar` and
`atc-lib.jar`, and fails closed on a missing or mismatching sha256 `digest`; the marker
`release.txt` holds `id|tag`, and a cache whose jars no longer match their digests is
re-downloaded.

`atc dev <checkout>` (`install_dev_build`) is the developer mode: it copies
`<checkout>/out/dist.dest/{atc.jar,atc-lib.jar}` over the cached jars and writes
`dev|<checkout>` to the marker, so `cached_release_matches` fails and `atc update` downloads
the release again, and `cmd_run` (via `dev_source`) says on stderr that a local build is
running. It never builds (a staleness note comes from `find -newer` over `build.mill`,
`app/`, `lib/`); `./mill dist` or `start.sh` does that.

`tests/atc_test.sh` sources the script (its guarded `main` does not run) and covers
release-metadata parsing (both parsers), checksum verification, the cache checks, the PATH
snippet, locations, command dispatch with a mock `java`, the dev mode, and the download flow
against a stubbed GitHub; no network or Java needed.

Windows releases instead put `atc.ps1`, `atc.cmd`, `atc.jar`, and `atc-lib.jar` together.
The PowerShell-native `atc.ps1` is the lossless application launcher; `atc.cmd` is retained
for Command Prompt compatibility but necessarily has batch-file argument parsing. Both
accept application flags such as `atc.ps1 --help`, not the Unix wrapper's
`setup`/`update`/`self`/`dev` commands. Updating is currently a manual replacement of all
four assets from one release. For a checkout,
`start.cmd`/`start.ps1` load `.env`, rebuild through `mill.bat` when stale, and then launch
the local distribution without requiring Bash. Both Windows launch paths carry application
arguments through private child-environment entries because `java.exe` otherwise converts
its UTF-16 command line through the legacy ANSI code page. ATC removes those entries from
the environment of every tool process it starts.

## Releases and CI

CI (`.github/workflows/scala.yml`, modelled on TACIT's) runs on Linux, macOS, and Windows.
Unix jobs use `./mill`; Windows uses `mill.bat` and serial tests. Every platform builds the
distribution and runs the application test suite; the test step also runs after a packaging
failure so both results are visible. Linux checks formatting, and non-Windows jobs run the
deterministic Bash-wrapper test suite. CI deliberately does not run end-to-end launcher
smoke scenarios: they couple shell parsing, packaging, terminal setup, configuration and the
Scala REPL while duplicating behavior covered by the focused tests.

Publishing a GitHub release whose tag is `v<Versions.atc>` (`build.mill`; the bare version
is accepted too) makes the `publish-release` job (needs `build`) check the tag against the
version, run `./mill dist`, and upload `atc.jar`, `atc-lib.jar`, `atc.ps1`, and `atc.cmd`.
The two jar names are the exact assets the Unix wrapper looks for; Windows users download
all four assets.
A mismatching tag fails the job with a message saying which side to fix.
