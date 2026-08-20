# ATC: development notes

Everything about how ATC is built and why it is built that way. The [README](../README.md)
is the user's guide (installing, driving the agent, the capability idea); this document is
for working on ATC itself: building and testing, the architecture, the capability design in
depth, the sandbox, the permission model, the exact semantics of the configuration, the
terminal, and the conventions and gotchas that are easy to trip over.

Contents: [Building and running](#building-and-running) · [Architecture](#architecture) ·
[The capability design](#the-capability-design) · [Defence in depth](#defence-in-depth) ·
[The sandbox](#the-sandbox) · [The permission model](#the-permission-model) ·
[Configuration semantics](#configuration-semantics) · [Models and providers](#models-and-providers) ·
[What the model sees](#what-the-model-sees-the-context) · [The agent loop](#the-agent-loop) · [The terminal](#the-terminal) ·
[Testing](#testing) · [Conventions and gotchas](#conventions-and-gotchas) ·
[The wrapper script](#the-wrapper-script) · [Releases and CI](#releases-and-ci)

## Building and running

The repository ships its own Mill launcher (`./mill`), so a JDK 17+ is all you need. The
Scala version is a pinned nightly (`Versions.scala` in `build.mill`): capture checking and
safe mode are experimental and move fast, so ATC tracks one known-good build rather than a
release.

```bash
./mill app.test                                   # all tests (munit, about 20 s)
./mill app.test.testOnly atc.ReplSessionSuite     # one suite
./mill app.test.testOnly atc.ReplSessionSuite -- '*timeout*'   # tests matching a glob
./mill app.compile                                # compile app (+ lib)
./mill __.checkFormat                             # scalafmt check; ./mill __.reformat fixes
./mill dist                                       # self-contained out/dist.dest/{atc,atc.jar,atc-lib.jar}
./start.sh -C ~/some/project                      # sources .env, rebuilds dist if sources changed, runs the TUI
./start.sh -c cfg.json -p 'run: 1 + 1'            # one non-interactive turn (plain mode)
./mill -i app.run -C /some/project                # dev run without dist (-i keeps the terminal attached)
./mill app.test.runMain atc.Scratch file.scala [nosafe] [preamble.scala]   # run `// ---`-separated snippets in a sandbox
ATC_SKIP_BUILD=1 ./start.sh ...                   # skip the rebuild check
bash tests/atc_test.sh                            # tests of the `atc` wrapper script (bash 3.2+, no network, no Java)
```

`start.sh` is the developer path: it rebuilds `out/dist.dest/` with `./mill dist` when a
source file is newer than the jar, sources a `.env` (`cp .env.example .env`; API keys and
the `ATC_*` variables below, without overriding what the shell already exported) and passes
its flags through to `atc`. Without the script: `./mill dist`, then `out/dist.dest/atc`.

To run a local build through the *installed* `atc` wrapper instead, `atc dev <checkout>`
copies the checkout's `out/dist.dest/` jars into `~/.atc/jars/` in place of the release; see
[The wrapper script](#the-wrapper-script).

Environment variables: `ATC_DEBUG=1` (`atc.Debug`) prints stack traces and terminal/stream
diagnostics; `ATC_ASCII=1` draws the TUI with ASCII glyphs; `ATC_JAVA_OPTS` adds JVM flags
(wrapper and `start.sh`); `ATC_MODEL`, `ATC_CONFIG`, `ATC_CWD` are `start.sh` shorthands for
`-m`, `-c`, `-C`. A provider with `"api": "echo"` is a key-less model (`run: <code>` in the
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
| `lib`  | The one API the model programs against: `atc.lib.Interface` plus the capability and data types (`FileSystem`, `Classified`, `Todo`, …), compiled with capture checking (`-language:experimental.captureChecking`, `-Wsafe-init`), every agent-visible definition `@assumeSafe`. No implementation lives here; the sandbox injection point (`atc.lib.Runtime`, holding `current` and the root capabilities, all `@rejectSafe`) sits in its own file, outside the API source the model is shown. |
| `app`  | The agent program. `atc.host.Host` **implements `Interface` directly** (permission policy, file/process/network effects, questions, TODO list, LLM calls), and the REPL preamble binds that implementation as `api`, so a call in agent code is a plain method call on the host, with no marshalling layer to keep in sync. Also: sandbox and REPL management, LLM providers, terminal UI. |

```
lib/src/atc/lib/   Interface.scala: the agent-facing API (capabilities, data types, Interface)
                   Runtime.scala: the sandbox's injection point (@rejectSafe, not part of the API)
app/src/atc/
  (root)           Main → App: command line, wiring, the interactive loop and its slash commands
  agent/           the loop (model ⇄ run_scala), the system prompt and tool spec, next-input prediction
  config/          the JSON config model: layers, merging, validation, keys, model catalog, templates
  host/            the Interface implementation: policy checks, file/process/network effects, Classified
  llm/             provider-neutral messages and ChatModel, plus the Anthropic, OpenAI and echo adapters
  perms/           the permission policy: rules and path patterns, scopes and grants, modes, gitignore
  sandbox/         the in-process REPL: preamble, validator, class-loader isolation, timeouts, interrupts
  ui/              the JLine terminal: streaming output, panels, pop-ups, Markdown and Scala colouring
app/test/src/atc/  munit suites, one per guarantee (TestEnv + ReplAssertions are the shared fixtures)
app/resources/atc/ the starting configs (config-template.json, project-template.json, keys-template.properties)
atc                the wrapper: installs, updates and runs the release jars (tests/atc_test.sh)
start.sh           builds a checkout and runs it
capture-checking-bug/  a runnable repro of an upstream separate-compilation bug (see below)
```

### Data flow of one turn

`App` (wiring) → `Agent.turn` → `ChatModel.complete` → tool call `run_scala` →
`ReplSession.run(code)` → `CodeValidator` (regex pre-check) → REPL compile + eval with the
`Host` installed as the API implementation → `ExecutionResult` → `Agent.renderForModel`
(bounded, hint-annotated) back into `Msg.ToolResults` → the model again, until it answers.

`ChatModel.complete` takes one `SystemPrompt(text)` built by `Prompts.system` from the
configuration, the working directory and the mode, the configured permissions included;
nothing in it changes during a session (a mode switch restarts the sandbox anyway), and a
permission the user grants for the session is reported in the tool result of the call that
asked. So every request of a session is the same prefix plus what was appended, whatever
caching the provider offers (see [What the model sees](#what-the-model-sees-the-context)).
Completions stream into a `StreamSink` (text / notes / thinking) that the UI renders live.

History (`llm.Msg`) is provider-neutral, so `/model` can switch vendors mid-conversation;
each provider stashes a `NativeTurn` on the assistant message for exact replay when the
same provider continues.

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

Editing helpers in the API: `replace(path, target, replacement)` is the targeted edit (it
returns the occurrence count and throws when the target is absent, so a mistyped pattern
cannot look like a successful edit), `write`/`writeBytes` rewrite a whole file,
`readBytes`/`writeBytes` are the binary pair.

## The capability design

This expands the README's [idea section](../README.md#the-idea-capabilities-instead-of-ambient-authority)
with what the implementation relies on.

**Capabilities cannot be forged.** The capability classes have `private[atc]` constructors
(agent code is compiled into the empty package), the only instances are the ones the REPL
preamble binds, and `atc.lib.Runtime` (`current`/`rootIO`/`rootUser`/`install`) is
`@rejectSafe`: under safe mode agent code cannot even name it. The regex validator refuses
the names anyway, as a second line.

**Mutability / read-only tracking** follows the nightly's `mutability.md`: the mode-tracked
capabilities (`IOCap`, `UserIO`, `FileSystem`, `FileEntry`) extend
`atc.lib.Cap = caps.Stateful, caps.ExclusiveCapability`. A bare type is the read-only view
(`^{any.rd}`); `^`/`^{io}` is full. The write side (`FileEntry.write/append/delete/mkdir/
writeClassified`) are `update def`s, callable only through a full capture set. `x.rd` names
the read-only view of `x` (`val ro: IOCap^{io.rd} = io`), and `readOnlyFileSystem` hands
out a `FileSystem^{io.rd}` for helpers that should be provably unable to write.

**Two roots.** `IOCap` derives `fs`/`ex`/`net` and is read-only in local and read-only mode
(so no writable `fs`, `Exec` or `Network` can be derived from it); `UserIO` is *always* full
(`given user: UserIO^`) and is what `println`/`print`/`printf`/`ask`/`setTodos`/`markTodo`/
`chat(String)` and every `request*` take, so reporting and permission prompts work in every
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
only. Every outward channel needs a full capability (`println`/`ask`/`chat`/`setTodos` →
`UserIO^`; `write`/`append` → `FileSystem^`; `exec` → `Exec`; `httpGet` → `Network`;
`request*` → `UserIO^`), so none of them compile inside `map`; reading files does compile
where `fs` is itself read-only (read-only mode). Do **not** loosen any of those to a
read-only capability without re-running the leak audit (`CapabilitySuite`).
`chat(message: String)` in particular used to be the hole that made a bare `{any.rd}`
unsound.

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

Types decide what compiles; four more layers sit underneath, each independent of the others.

1. **The policy at run time.** Every host method checks the permission `Policy` for the
   path, command or host (and the scope id of the capability it was called through);
   `Policy.mode` enforces the three modes again (writes downgraded to read, `exec`/network
   refused), so nothing rests on the type check alone.
2. **The validator** (`sandbox/CodeValidator.scala`), a regex pre-check before compilation:
   it blocks `atc.(host|agent|sandbox|perms|config|llm|ui)`, reflection, `java.io/nio/net`,
   `System.out/err/in`, `System.exit/setProperty/getenv/getProperty/load*`, `caps.unsafe`,
   `Runtime.current/rootIO/rootUser/install`, and catching fatal throwables (`catch case _:
   Throwable/Error/…`, a bare `catch case _ =>` via a cross-line rule, and any
   `InterruptedException`/`ThreadDeath`). Fatal throwables (a real SOE/OOM, or the
   `ThreadDeath` stop signal) deliberately propagate out of `Classified.map` and abort the
   run; forbidding the catch is what stops them being used as a per-bit oracle over
   classified data or to swallow a timeout/interrupt. Add rules there for new escape
   hatches, and tests to `CodeValidatorSuite`.
3. **Class-loader isolation** (`sandbox/Sandbox.scala`): the REPL's class loader has
   `SandboxLoader` as parent, which delegates only `scala.*` and `atc.lib.*` to the app
   loader and everything else to the platform loader, so agent code cannot see `atc.host`,
   the LLM clients, JLine or the compiler, and the sandbox classpath holds no third-party
   library. `getResource` hides `*.class` so `-Xrepl-interrupt-instrumentation:true`
   instruments only REPL-defined classes (otherwise the REPL would re-define
   `atc.lib.Interface` and lose the installed host). One sandbox per JVM: `Interface`'s
   companion holds the installed host.
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
worker thread; `ExecutionClock.paused` excludes time waiting for the user (permission
prompts, questions), so a slow human does not trip the timeout; interrupt/timeout raise the
REPL stop flag through `OpenReplDriver` (a subclass of the compiler's `ReplDriver`; it also
installs `CappedRendering`, which lives in **package `dotty.tools.repl`** because
`Rendering` is `private[repl]`) and `skipPoisonedWrapper()` advances the wrapper index so a
half-initialized class name is never reused. `clock.reset()` happens at the top of every
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
`System.nanoTime` are unavailable (use `java.time`). Writing helpers need
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

**Every path the host hands out is canonical** (`Host.canonical` for paths given by name,
`visibleEntries` for listings): a symlink is listed as, and judged by, its target, so an
entry from `children`/`walk` is checked exactly like the same path given by name (a link in
a readable directory to a classified file is classified through the listing too; regression
test in `HostSuite`), and only links pay for `toRealPath` since a plain child of a canonical
directory is canonical. Symlinked directories are listed but never entered.

**Classified paths.** Content is only observable as `Classified[String]`; a classified
directory's structure is classified too (listing needs `childrenClassified`/
`walkClassified`, returning `Classified[List[String]]`; `walk`/`grepRecursive`/`find` do not
descend into it). A plain `write` to a classified path is refused (use `writeClassified`),
and `writeClassified` to a non-classified path is refused too, since it would declassify.

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

**Three layers**: global (`~/.atc/config.json`) ← project (the nearest `.atc/config.json`
at or above cwd, found by `Config.projectRoot` walking up; a `.atc` holding *either*
`config.json` or `keys.properties` counts as a project) ← explicit (`-c file`). **No
permission is granted anywhere in the program, and no file is written without asking**:
`App.setup` (interactive runs only; `-p` runs ask nothing) offers, when
`~/.atc/config.json` is missing, to write `config-template.json` + `keys-template.properties`
(`Config.ensureGlobal`), otherwise loads the template as an in-memory `Origin.Global` layer
with `path = None` (`Config.load(..., bundledGlobal = true)`, shown by `/config` as
`(bundled)`); then, when no config grants cwd and cwd has no `.atc/config.json` of its own,
offers to write `project-template.json` + `.atc/.gitignore` (`Config.initProject`, shared
with `--init`) and reloads; and if the global config was written it ends the program with
`App.Exit(0)` so the user can fill in the keys. Once written, a config is never touched
again except by `/model`/`/classifiedmodel` (below). The templates protect and grant
nothing: the global one classifies the usual credential paths (`.ssh`, `.gnupg`, `.env`,
`.env.*`, `.netrc`, `.npmrc`, `.pypirc`, `.docker`, `.kube`, `.aws`, `.azure`, `.gcloud`,
`*.pem`, `id_rsa`, `id_ed25519`), sets `.atc` to `none` and `locked` (the pattern has no
`/`, so it covers `~/.atc` and any project's `.atc` alike: the agent can read neither the
config nor the keys, and no prompt can open them), denies `rm -rf *` and `sudo *`, and
grants no file, command or host; the project template is what opens a project: its tree with `./.git` read-only (git *commands*
are governed by `commands`, not file rules) and `./secrets` classified, the read-only git
commands (and `git push*`/`git reset --hard*` denied), and documentation hosts (official
docs and paper hosts, not package registries or code hosting, since a permitted host is
also somewhere `httpPost` can send data).

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

`ChatModel.simple(system, prompt, thinking)` is the one-shot call (`chat()` from the
sandbox, the next-input prediction). With `thinking = false` it disables Anthropic thinking
and sends OpenAI the lowest `reasoning_effort` the model family takes
(`Providers.lowestEffort`: `none` ≥ 5.1, `minimal` GPT-5, `low` o-series or any model the
config gives an effort; nothing for models not known to reason), or `disabled` when the
vendor thinking switch is configured; a `BadRequestException` on that guess sets
`effortRejected` on the model (`OpenAIShapedModel.withEffortFallback`) and the request is
repeated plainly, once per process. `thinking = true` applies the configured
thinking/effort, the same as `complete`.

**API keys**: a provider keeps `key`/`keyEnv` naming a `${VAR}`; the *values* come from
`.atc/keys.properties` (`config/Keys.scala`, read with `java.util.Properties`, so
`NAME=value`, `NAME: value`, `#`/`!` comments, `\` escapes and line continuations).
`KeyBindings.get` tries the project file, then `~/.atc/keys.properties`, then
`System.getenv`; an empty value is not a binding, so the lookup falls through.
`Config.resolveApiKey(provider, bindings)` feeds `ModelCatalog.from(config, keys)`.
`ModelSpec.toString` masks the key and `/config` prints variable names only, never values.

**Context window.** `ModelConfig.contextWindow: Option[Tokens]` (`config/Tokens.scala`, an
opaque `Int` whose upickle reader takes a number or `"256k"`/`"1m"`; decimal multipliers) →
`ChatModel.contextWindow: Option[Int]`. Before every model call `Turn.fitHistoryToContext`
drops whole exchanges from the front of `agent.history` (`Agent.fitToContext`, cuts only at
`Msg.User` boundaries, keeps the last user message) until `estimateTokens` (chars/4 ×
`tokenCalibration`, the ratio observed prompt tokens / estimate from the previous
completion; `TokenUsage.input` is the whole prompt for every provider, Anthropic's cache
reads/writes included) fits `window − window/8 − system prompt − tool schema`; the first
kept user message gets `Agent.contextCutNotice(total dropped)` prepended and the UI warns.
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
never change within a session come first and the history last. A **turn** is one user
message and everything until the model's final answer; within it, every **round** (one
model call) re-sends the whole context so far, plus the tool results of the previous round.

```
┌ system prompt (one text, the same for the whole session) ──────────────────┐  Prompts.system(...).text
│ identity: "a coding agent with tracked capabilities … acts only by         │  changes only with cwd, config,
│   writing Scala and running it with run_scala"                             │  mode (→ REPL restart)
│ Environment: working directory, OS, REPL flags, whether a classified       │
│   model is configured (never which), the gitignore note                    │
│ How to work: orient first (find and read AGENTS.md/CLAUDE.md/README/…,     │
│   learn the build and test commands from them and the build files),        │
│   explore → replace/write → verify with exec → println, request* on        │
│   "Access denied", session grants are reported in results, do not          │
│   retry capability compile errors, small snippets, todos/ask, never        │
│   end on a plan                                                            │
│ Sandbox mode paragraph (Prompts.modeSection): the givens of the mode       │
│   and what does not compile in it                                          │
│ Rules of the sandbox: what is forbidden, read-only vs full views,          │
│   top-level val/var quirks, Option/effects, fatal throwables, Classified   │
│ API reference: the full source of lib/…/Interface.scala                    │  Prompts.interfaceSource
│ Project instructions: config "instructions", if any                        │
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

**The system prompt** is one text, `SystemPrompt(text)`, and it is the same for the
whole session on purpose: everything in it (including the permission summary,
`Policy.configSummary`, which leaves session grants out) changes only together with a
sandbox restart (mode switch, config). That is the general, provider-independent way to
keep a request cacheable: the request is always the same prefix plus what was appended
since, so automatic prefix caching (OpenAI-shaped APIs) hits up to the newest messages,
explicit breakpoints (Anthropic: one on the system block and one on the last message of the
history, so each round reads the previous round's prefix and writes only the new messages)
do too, and a provider with no cache loses nothing. The alternative, a "current
permissions" block kept up to date in the prompt, would invalidate the cached history on
every grant (or, with a single breakpoint on the system prompt, never cache the history at
all), which is what an earlier design did. The prompt embeds the *whole* source of
`Interface.scala` (the same text `/interface` prints), which is why wording in that file is
prompt wording, and a mode paragraph, which is why a mode switch restarts the REPL and
re-renders the prompt. It says *whether* a classified model is configured (so the model
knows if `chat(Classified)` works), never which one. `Agent.systemPrompt` is recomputed
before every round from the live `Policy`, so nothing is cached on the ATC side; it just
comes out the same.

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
said and done, never the terminal's rendering:

* `Msg.User(text)`: the user's request. *Pending notes* are prepended to it, each on its
  own paragraph, so the transcript never has two user messages in a row: `[sandbox notice]`
  (the REPL was restarted by `/reset` or a mode switch: definitions are gone),
  `[user ran code]` (what the user ran with `/run`, as a fenced block, and its rendered
  result), and, on the first kept message after a context cut, `[context notice] The N
  oldest messages … were dropped`. The loop's own `ContinueNudge` ("you ended your turn
  with a plan …") is also a user message. Notes are *prepended* to the next user message
  rather than inserted as messages of their own, again so that nothing already sent changes.
* `Msg.Assistant(text, toolCalls, native)`: the model's prose and its `run_scala` calls
  (`ToolCall(id, name, arguments)`, the code as JSON), plus the provider's `NativeTurn` for
  exact replay when the same provider continues (Anthropic content blocks including
  server-side web-search results, Responses output items); another provider gets the
  neutral text and calls. An interrupted turn ends with `Assistant("[interrupted by
  user]")` so the history stays well-formed.
* `Msg.ToolResults(results)`: one `ToolResult(callId, output, isError)` per call, where
  `output` is `Agent.renderForModel(result, config.maxToolOutputChars)`:
  `ExecutionResult.render` (the captured REPL output with host stack frames trimmed, then
  `ERROR: <message>` for a compile/validation/timeout error, or `(no output)` /
  `(failed, no output)`), a `Hint:` line when `Agent.hints` recognises the error (read-only
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
thinking blocks, Responses reasoning items) when the same provider continues; the neutral
history, and so any other provider, has only the text and the calls.

**Fitting the window.** Before every model call, when the model has a `contextWindow`,
`Turn.fitHistoryToContext` estimates the request (`fixedTokens` = system prompt + tool
schema, plus `Agent.estimateTokens` of every message, chars/4 scaled by the calibration
from the provider's last prompt count) against `window − window/8` and drops whole
exchanges from the front (`Agent.fitToContext`: cuts only at `Msg.User` boundaries so no
tool result loses its call, and always keeps the last user message); `contextDropped`
accumulates so the notice states the total. The same estimate is `Agent.contextUsage`,
shown after each turn and by `/cost`.

**The other model calls have their own, smaller contexts.** `chat(message)` from agent
code is a one-shot `ChatModel.simple(None, message)` with no history; `chat(Classified)`
goes to the classified model with a one-line system prompt ("a trusted assistant handling
confidential data") and the secret as the only content; the next-input prediction sends
`InputPredictor.System` and a rendered transcript of the last `Exchanges` user/agent text
pairs (tool calls and results left out, each message cut to `MessageChars`), never to the
classified model. All of them are recorded under their own purpose in `/cost`.

## The agent loop

`Agent.turn` is a loop of *rounds* (`Turn.round`: ask the model, then run tools / resume /
nudge / stop) with per-turn counters against `Agent.Max*` and `config.maxToolCalls`:

* tool calls → run them (`run_scala`, one at a time), append the results and ask again;
* `unfinished` (the provider paused after a server-side tool such as web search, Anthropic
  `pause_turn`) → ask again so the model resumes, at most `MaxResumes` times;
* a reply that merely announces a next step ("Let me check…", `Agent.looksUnfinished`) →
  nudge the model to act, at most `MaxNudges` times;
* the exhausted tool budget is reported to the model as an error result, and the turn is
  stopped after `MaxBudgetRejections` rounds of it insisting;
* anything else is the final answer.

`cancelled` is polled while streaming and before every tool call; an interrupted turn ends
with an `[interrupted by user]` assistant message so the history stays well-formed. A
refusal stop reason is shown to the user.

**Pending notes.** Things the model must hear at the start of its next turn (`[sandbox
notice]` after a REPL restart, `[user ran code]` after `/run`) are queued in an ordered
list and prepended to the next user message, so the transcript never holds two user
messages in a row; `clear()` drops them.

**Next-input prediction** (`agent/InputPredictor.scala`, config `predictInput`, default
on): after each interactive turn `App` calls `predictor.start()`, which asks
`agent.model.simple` for the likely next request on a daemon thread and hands it to
`Tui.suggest`; the TUI draws it as faint ghost text through a `DefaultHighlighter` subclass
(display only: JLine positions the cursor from the buffer), and Tab / → accept it
(`atc-accept-suggestion-*` widgets fall back to the previous binding). A generation counter
drops guesses that arrive after `invalidate()` (next turn, `/clear`, `/new`); `Tui.suggest`
redraws via `callWidget(REDISPLAY)`, which JLine only honours while reading. Plain mode and
`-p` runs never predict; the classified model is never used.

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

All output goes through one `write` that tracks the last two chars (`tail`) so gutters can
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
result does not carry them). Quick commands show nothing. Agent code cannot write to
`System.out/err` itself (validator), so only `println` and this are live; the REPL's
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

All tests are munit, under `app/test/src/atc/`, and run in about 20 s. They share
`TestEnv.scala` (temp root, scripted permission decisions, recording host output, live
command output, `newSession`, `activate()`) and `ReplAssertions.scala` (`assertOk`/
`assertFails` for REPL snippets); `AgentLoopSuite` drives the loop with `ScriptedModel`/
`RecordingUI` without a network. `AgentUI`, `HostOutput`/`HostLlm`/`HostUi`, `ChatModel`
and `StreamSink` all have test doubles; changing those traits means updating them (the
two `HostOutput` live-output methods have no-op defaults for that reason).

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
  isolation, validator/fatal-throwable safety nets).
* **`ReplSessionSuite`**: REPL mechanics (language coverage, errors, timeouts, interrupts,
  output capture and caps, REPL command allow-list).
* **`ClassifiedSuite`**: `Classified` semantics against the host directly.
* **`PermissionSuite`** / **`PolicySuite`** / **`HostSuite`**: the permission model, the
  policy algebra, the host's path canonicalisation, exec (incl. live output) and network.
* **`LayerSuite`**, **`ConfigSuite`**, **`ModelSuite`**, **`GitIgnoreSuite`**,
  **`CodeValidatorSuite`**: configuration layering, the config model, the model catalog and
  adapters, gitignore matching, the validator rules.
* **`AgentLoopSuite`**, **`AgentSuite`**, **`InputPredictorSuite`**: the loop, the system
  prompt and rendering for the model, the context cut, pending notes, usage, prediction.
* **`TuiSuite`**, **`RenderSuite`**, **`SlashCommandSuite`**: the terminal's pure helpers
  (row placement, print subtraction, number formatting, the continuation rules), the
  Markdown/highlighting renderers, the slash-command table.

The installed host is process-global (one sandbox per JVM), so a suite holding more than
one live session must call `env.activate()` before each `run` (`ModeSuite` keeps one
session per mode this way); otherwise a session picks up another env's host when its `api`
object is first forced. `Scratch` (`./mill app.test.runMain atc.Scratch file.scala`) runs
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

`atc` (repo root, bash 3.2+) is the user-facing installer/launcher, modelled on TACIT's
`tacit`: `atc setup` (install to `~/.local/bin`, PATH snippet in the shell profile, Java 17+
check, download), `atc update`, `atc self update|uninstall`, and anything else (`atc`,
`atc -C dir`, `atc run ...`) execs `java -Datc.lib.classpath=atc-lib.jar -jar atc.jar` from
`~/.atc/jars/` (beside the global config, in its own directory so uninstall's `rm -rf` never
touches `config.json`/`keys.properties`; `ATC_CACHE_DIR`/`ATC_INSTALL_DIR` override, used by
the tests). It fetches `releases/latest` from the GitHub API (`GITHUB_TOKEN` raises the
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

## Releases and CI

CI (`.github/workflows/scala.yml`, modelled on TACIT's) runs `tests/atc_test.sh`, sets up
JDK 17, then `__.checkFormat`, `__.compile`, `app.test`, `dist`, and a key-less echo-model
smoke run of the built `atc` (`-p 'run: println("ci"); 21 * 2'`, grepping the echoed result;
keep that expectation in sync if the tool-result echo format changes).

Publishing a GitHub release whose tag is `v<Versions.atc>` (`build.mill`; the bare version
is accepted too) makes the `publish-release` job (needs `build`) check the tag against the
version, run `./mill dist` and `gh release upload` `atc.jar` + `atc-lib.jar`, the exact
asset names the wrapper looks for. A mismatching tag fails the job with a message saying
which side to fix.
