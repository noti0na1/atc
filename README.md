# ATC — A Minimal Agent With Tracked Capabilities

[![Scala CI](https://github.com/noti0na1/atc/actions/workflows/scala.yml/badge.svg)](https://github.com/noti0na1/atc/actions/workflows/scala.yml)

ATC is a small terminal coding agent (in the spirit of Claude Code) whose **only tool is a
Scala 3 REPL** protected by [capture checking](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/index.html)
and Scala's experimental *safe mode*. Every action the model wants to take — read a file,
run a command, fetch a URL — has to be written as Scala against a small, capability-typed
library, and that code is compiled before it is allowed to run.

What the agent may do is therefore decided by **which capabilities are in scope**, and an
attempt to exceed them is a compile error rather than a runtime check somebody had to
remember to write. The ideas come from [TACIT](https://github.com/lampepfl/tacit)
(*Securing Agents With Tracked Capabilities*, CAIS '26); ATC re-packages them as a
self-contained agent instead of an MCP server, with a redesigned file-permission model and
interactive permission requests.

## What a turn looks like

```
> which methods in the library can mutate a file?

● run_scala
  │ grepRecursive("lib/src", "^\\s+update def", "*.scala")
  │   .foreach(m => println(s"${m.lineNumber}  ${m.line.trim}"))
  ├ output
  │ 116  update def write(content: String): Unit
  │ 117  update def writeBytes(content: Array[Byte]): Unit
  │ 118  update def append(content: String): Unit
  │ 119  update def delete(): Unit
  │ 121  update def mkdir(): Unit
  │ 130  update def writeClassified(content: Classified[String]): Unit
  └ ok 121 ms

● Six of them — write, writeBytes, append, delete, mkdir and writeClassified. They are
  declared `update`, so they can only be called through a full `FileSystem^`.
```

`grepRecursive` and `println` are not strings ATC parses: they are methods of
`atc.lib.Interface`, each demanding a capability value that only the sandbox hands out.
The snippet was compiled before it ran — had it tried to write a file in read-only mode,
or reach the network without a `Network`, nothing would have happened. The REPL keeps its
state between snippets, so a `val` defined in one turn is still there in the next.

## Setup

You need JDK 17 or newer. The repository ships its own Mill launcher (`./mill`), so there
is nothing else to install.

**1. API keys.** Copy the example environment file and fill in the providers you use:

```bash
cp .env.example .env       # ANTHROPIC_API_KEY / OPENAI_API_KEY, plus optional ATC_* settings
```

`start.sh` sources this file without overriding variables your shell already exports. Skip
it if you export the keys yourself, or use a local model that needs none.

**2. A project config.** Run ATC once with `--init` in the project it should work on:

```bash
./start.sh -C ~/my-project --init      # writes ~/my-project/.atc/config.json
```

Then open that file and decide two things: which **models** to use, and which **files,
commands and hosts** the agent may touch without asking. Both have working defaults; the
permissions are the part worth reading — see [Configuration](#configuration).

**3. Run it.**

```bash
./start.sh -C ~/my-project
```

`start.sh` rebuilds `out/dist.dest/` with `./mill dist` when sources changed, and passes
its flags through to `atc`. Without the script: `./mill dist`, then `out/dist.dest/atc`.

Useful flags: `-m <alias>` pick a model, `--mode readonly|local|full` pick a sandbox mode,
`-p "<request>"` run one turn and exit, `-c <file>` add a config file, `-C <dir>` set the
working directory, `--approve-all` auto-approve permission requests (scripted use only).

### Trying it without an API key

`"provider": "echo"` is a built-in model that echoes your text and turns a message of the
form `run: <code>` into a `run_scala` call. It needs no key, and is the quickest way to see
the sandbox, the modes and the permission pop-ups for yourself:

```bash
mkdir -p .atc && cat > .atc/config.json <<'EOF'
{ "model": "echo", "safeModel": "echo", "models": { "echo": { "provider": "echo", "model": "echo" } } }
EOF
out/dist.dest/atc -p 'run: println(read("README.md").take(80))'
out/dist.dest/atc --mode readonly -p 'run: write("notes.md", "hello")'   # a compile error
```

### Working on ATC itself

```bash
./mill app.test                  # ~200 munit tests, about 20 s
./mill -i app.run -C ~/project   # run without building a dist (-i keeps the terminal attached)
./mill __.reformat               # scalafmt; CI runs ./mill __.checkFormat
./mill dist                      # self-contained out/dist.dest/{atc,atc.jar,atc-lib.jar}
```

`ATC_DEBUG=1` prints stack traces and terminal diagnostics, `ATC_ASCII=1` draws the UI with
ASCII glyphs only, `ATC_SKIP_BUILD=1` makes `start.sh` skip its rebuild check.

## Capabilities

In ordinary Scala, any code can call `Files.readString` or start a process: authority is
*ambient*, available to anyone who can name the method. The agent-facing library takes that
away. Every effectful method demands the capability it needs as a `using` parameter:

```scala
def read(path: String)(using FileSystem): String
def write(path: String, content: String)(using FileSystem^): Unit
def exec(command: String, args: List[String])(using Exec, FileSystem): ProcessResult
def httpGet(url: String)(using Network): String
def println(x: Any)(using UserIO^): Unit
```

A snippet can therefore do exactly what the givens in its scope allow, and it cannot conjure
a new one: the capability classes have `private[atc]` constructors (agent code is compiled
into the empty package), the only instances are the ones the REPL preamble binds, and the
object holding the roots (`atc.lib.Runtime`) is `@rejectSafe` — under safe mode, agent code
cannot even name it.

### The capabilities, and where they come from

The preamble binds one root for effects on the machine and one for talking to the human.
Everything else is derived from the first:

```
                        ┌── fs:  FileSystem^{io}    read files; write with a full view
Runtime.rootIO  ──► io ─┼── ex:  Exec^{io}          run permitted commands
                        └── net: Network^{io}       reach permitted hosts

Runtime.rootUser ──► user: UserIO^                  println · print · ask · setTodos · chat
```

| Capability | What it authorises | Where one comes from |
|---|---|---|
| `IOCap` | nothing by itself — it is the root the others are derived from | the preamble (`given io`) |
| `FileSystem` | `read`, `ls`, `walk`, `grep`, …; `write`, `append`, `delete`, `mkdir` need a full one | `fileSystem(using io: IOCap^)`, or the preamble's `fs` |
| `FileEntry` | a handle to one file or directory; as capable as the `FileSystem` it came from | `fs.access(path)` |
| `Exec` | running commands | `processes(using io: IOCap^)` |
| `Network` | HTTP requests | `network(using io: IOCap^)` |
| `UserIO` | printing, questions, the TODO list, `chat` with the normal model | the preamble (`given user`), always full |

`UserIO` is deliberately *not* derived from `IOCap`: reporting to the human is an effect on
the conversation, not on the machine, so it survives even when the agent may touch nothing
at all (see [Modes](#modes-read-only-local-full)).

### Read-only and full views

Each of these types has two views, following the nightly's
[mutable-capability model](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/mutability.html):

* the **bare** type — `FileSystem`, `IOCap` — is the **read-only** view;
* `^`, or `^{io}` ("as capable as `io`"), is the **full** view.

The mutating operations are declared `update def` in the library, and an `update` method can
only be called through a full capture set. That one rule is what turns "read-only" from a
runtime check into a typing rule:

```scala
val e: FileEntry^{fs} = fs.access("notes.md")   // as capable as `fs` itself
e.read()                                       // fine through either view
e.write("hello")                               // only if `fs` is the full view
```

With a read-only `fs`, that last line is the whole security argument in one message:

```
Cannot call update method write of e
since its capture set {e} is read-only.
```

It propagates into your own helpers, so a `def` that writes has to say so in its signature:

```scala
def save(path: String, text: String)(using fs: FileSystem^): Unit = write(path, text)
```

`x.rd` names the read-only view of `x` (`val ro: IOCap^{io.rd} = io`), and
`readOnlyFileSystem` hands out a `FileSystem^{io.rd}` when you want a helper to be provably
unable to write.

### Capabilities cannot escape

Because capture checking tracks capabilities in *types*, the compiler knows which values
hold which capability, and refuses the ones that would outlive their scope. That is what
makes [`request*` blocks](#asking-for-more) safe: the wider file system lent to a block
cannot be stashed in a `val` that survives it, returned from it, or captured by a closure
that escapes it.

### Classified data

Capabilities constrain *effects*. Confidential content gets a second, independent
discipline: `readClassified(path)` gives a `Classified[String]`, whose `map`/`flatMap` take

```scala
def map[B](op: T ->{any.rd} B): Classified[B]
```

`->{any.rd}` means the function may capture **read-only** capabilities only. Every outward
channel needs a *full* one — `println`/`ask`/`chat` need `UserIO^`, `write` needs
`FileSystem^`, `exec` needs `Exec`, `httpGet` needs `Network` — so none of them can appear
inside a `map`. The agent can compute on a secret (and even read files, where its `fs` is
itself read-only) but never see it; `toString` is `Classified(***)`.

The ways out are deliberate and few: `println` (the human sees the value in the terminal,
marked `[classified]`; the model sees `Classified(***)`), `writeClassified` into a
classified path, `chat(Classified)` with the configured safe model, and `httpPostClassified`
/ `secretHeaders` to an allow-listed host.

### What the types do not know

Types decide what compiles; they know nothing about your configuration. So every host method
*also* checks the permission [`Policy`](#file-permissions) for the path, command or host in
question, and every capability value carries the id of the permission scope it was issued
for — a capability from a `request*` block is refused once that block has closed. Two more
layers sit underneath: a regex `CodeValidator` rejects the obvious escape hatches
(`java.io`, reflection, `caps.unsafe`, `atc.host`, catching fatal throwables) before
compilation, and the REPL's class loader exposes only the JDK, `scala.*` and `atc.lib.*`, so
the application itself — host, policy, LLM clients, the compiler — is invisible to agent
code.

## Modes: read-only, local, full

A **mode** decides which capabilities the preamble puts in scope, and therefore what the
agent can express at all, before the permission policy even comes up.

| Mode | In scope | The agent can |
|---|---|---|
| **read-only** | `io: IOCap` (read-only), `fs: FileSystem^{io.rd}`, `user: UserIO^` | read files, report, ask |
| **local** | `io: IOCap` (read-only), `fs: FileSystem^`, `ex: Exec^`, `user: UserIO^` | also write files and run commands |
| **full** | `io: IOCap^`, `fs: FileSystem^{io}`, `ex: Exec^{io}`, `net: Network^{io}`, `user: UserIO^` | also reach the network |

Read that table through the two views above. In **read-only** mode `fs` is a read-only view,
so `write` is a call to an `update` method through a read-only capture set:

```
> write("notes.md", "hello")

Found:    (fs : atc.lib.FileSystem^{io.rd})
Required: atc.lib.FileSystem^{any}
… it cannot subsume a read-only capture set of the stateful type
  (fs : atc.lib.FileSystem^{io.rd}).
```

In **local** mode `fs` and `ex` are full, but `io` is only a read-only view — and `Network`
is derived by `def network(using io: IOCap^): Network^{io}`. There is no full `IOCap` to
derive one from, and none in scope, so a network call has nothing to resolve:

```
> def fetch(): String = httpGet("https://example.com")

No given instance of type atc.lib.Network was found for parameter x$2 of method httpGet
```

This is why `Exec` and `Network` hang off `IOCap` while `UserIO` has its own root: a mode
can withdraw the machine and leave the conversation intact, so the agent can always say what
it *would* have done. The system prompt tells it to do exactly that rather than look for a
way around a mode.

The `Policy` enforces the same three levels again at run time (writes downgraded to read,
`exec` and network refused), so nothing rests on the type check alone.

Switch modes with `/mode` (cycles read-only → local → full), **Shift-Tab** on an empty
prompt, `/mode <name>`, the `--mode` flag, or `"mode"` in the config. Switching starts a
fresh REPL — the agent's `val`s and `def`s are gone — but keeps the conversation. The
default is full.

## Asking for more

Anything the configuration already permits just works. When an operation is denied, the
exception names the block that can ask for it, and the agent wraps just that operation:

```scala
requestFiles("/tmp/data", Access.Write, reason = "cache build outputs") {
  write("/tmp/data/out.txt", "done")      // a wider FileSystem^ is the given inside the block
}
requestFiles("~/notes", Access.Read, "look up the design notes") {
  read("~/notes/design.md")
}
requestExec(Set("npm *"), "install deps") { exec("npm", List("install")) }
requestNetwork(Set("api.github.com"), "check PRs") { httpGet("https://api.github.com/...") }
```

You get a pop-up — *Yes, this time* / *Yes, for the rest of this session* / *No* — and the
block runs with the extra permission. `locked` rules cannot be widened at all. The granted
capability cannot leave the block (capture checking), and the host closes the permission
scope when the block exits, so its scope id can never be used again.

`requestFiles` works in every mode: the file system it lends the block is exactly as capable
as the one you already hold — full in local and full mode, read-only in read-only mode — so
the same call site compiles everywhere.

## Configuration

Config files are JSON, merged in this order: `~/.config/atc/config.json` (global),
`./.atc/config.json` (project), then `-c <file>`. Later files override scalars, extend
`files`/`commands`/`hosts`, and merge `models` by alias. `atc --init` writes
[`app/resources/atc/config-template.json`](app/resources/atc/config-template.json) as a
starting point.

```json
{
  "model": "claude",
  "safeModel": "local",
  "models": {
    "claude": { "provider": "anthropic",        "model": "claude-opus-5", "webSearch": true, "reasoning": "high" },
    "gpt":    { "provider": "openai-responses", "model": "gpt-5",         "webSearch": true },
    "chat":   { "provider": "openai",           "model": "gpt-4.1" },
    "local":  { "provider": "openai", "model": "llama3.1", "baseUrl": "http://localhost:11434/v1", "apiKey": "ollama" }
  },
  "files": [
    { "path": ".",        "access": "write" },
    { "path": "./build",  "access": "read" },
    { "path": "secrets",  "classified": true },
    { "path": "~/notes",  "access": "read", "locked": true }
  ],
  "commands": ["git status", "git diff*", "git log*", "ls", "./mill *"],
  "denyCommands": ["git push*", "rm -rf *", "sudo *"],
  "hosts": ["*.scala-lang.org", "docs.oracle.com"],
  "denyHosts": ["*.internal"],
  "safeMode": true,
  "respectGitignore": true,
  "mode": "full",
  "executionTimeoutMs": 180000,
  "maxToolCalls": 60,
  "instructions": "Use 2-space indentation."
}
```

### Models

`provider` is one of

* `anthropic` — Messages API (official Java SDK). `webSearch: true` adds the server-side
  `web_search` tool (`web_search_20260209`; set `"webSearchVersion": "20250305"` for older
  models). Adaptive thinking is on unless `"thinking": false`; `reasoning` maps to
  `output_config.effort` (`low|medium|high|xhigh|max`).
* `openai-responses` — Responses API; also works with other vendors that implement it (e.g.
  DeepSeek: `"baseUrl": "https://api.deepseek.com"`). `webSearch: true` adds the built-in
  `web_search` tool. `reasoning` maps to `reasoning.effort`; `"reasoningSummary": "auto"`
  asks OpenAI to stream reasoning summaries (shown as thinking; DeepSeek streams its
  reasoning without it).
* `openai` — Chat Completions; also the adapter for any OpenAI-compatible server (Ollama,
  vLLM, LM Studio, OpenRouter …) via `baseUrl`. `webSearch: true` sets `web_search_options`
  (only search-enabled models accept it).

API keys: `apiKey` (a literal or `"${SOME_ENV_VAR}"`), `apiKeyEnv`, or the SDK's own
resolution (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, …).

Two roles. **`model`** is the agent; it never sees classified data. **`safeModel`** is the
model that handles `Classified` values through `chat(Classified[String])` — point it at
something you trust with your secrets, typically a local model. The agent model can be
switched mid-conversation with `/model <alias>` (the history is provider-neutral); the safe
model is fixed by the config.

### File permissions

Each rule has a `path` pattern and any of `access` (`none|read|write`), `classified` (bool),
`locked` (bool). Patterns are gitignore-flavoured, as in TACIT:

* no `/` in the pattern → matches any path **component** anywhere (`.env`, `*.pem`,
  `node_modules`) — use `./name` for a project-relative directory;
* relative with `/` → relative to the working directory, with `*`, `**`, `?`, `[…]`;
* absolute or `~/…` → absolute; `.` is the working directory itself.

A rule applies to the path it matches **and its whole subtree**. The effective access of a
path is the **minimum** over all matching rules (a path matched by no rule with an access
level is inaccessible); it is **classified** if any matching rule says so, and **locked** if
any does. So a sub-folder inherits its parent's permission and can only be made stricter —
`build/generated: write` under `build: read` still yields `read`. If `files` is empty the
working directory is writable. The built-in classified patterns (`.ssh`, `.gnupg`, `.env`,
`.env.*`, `.netrc`, `.npmrc`, `.pypirc`, `.docker`, `.kube`, `.aws`, `.azure`, `.gcloud`,
`*.pem`, `id_rsa`, `id_ed25519`) are always added unless `"defaultClassified": false`.

`"respectGitignore": true` (the default) hides what git ignores: `ls`, `walk`, `find`,
`grepRecursive` and the `Classified` listings leave out `.git` and every path matched by a
`.gitignore` (the ones of the enclosing repository and any nested ones, with `!` negations,
`**`, directory-only `dir/` and anchoring as git reads them). This is *visibility*, not
permission: an ignored file is still readable and writable by name, so the agent can still
open `out/log.txt` if you ask it to. Set it to `false` to list everything.

**Classified** means the content is only observable as `Classified[String]`, and a
classified **directory's structure is classified too**: the directory is visible in its
parent, but listing it needs `childrenClassified`/`walkClassified` (returning
`Classified[List[String]]`), and `walk`/`grepRecursive`/`find` do not descend into it. A
plain `write` to a classified path is refused (use `writeClassified`), and `writeClassified`
to a *non*-classified path is refused too, since that would declassify the content. Symlinks
are judged by their target.

`commands` are patterns over the whole command line: `*` is a wildcard, and a pattern
without `*` matches by word prefix (`"git status"` allows `git status --short`; `"ls"`
allows `ls -la` but not `lsblk`). A command also needs read access to the directory it runs
in (the working directory by default), which must not be classified — that check goes
through the `FileSystem` capability, so a `requestFiles` block covers it. `hosts` are glob
patterns on host names; only `http`/`https` URLs are accepted.

`denyCommands` and `denyHosts` are the denylist to those allowlists, with the same pattern
syntax. A matching command or host is refused outright: **deny wins over every allow rule**,
over a session grant, over an open `request*` scope and over `--approve-all`, because it is
checked where the effect happens. A `requestExec`/`requestNetwork` that would permit
something denied fails immediately, without a pop-up — so `"denyCommands": ["git push*"]`
refuses both `exec("git", List("push"))` and `requestExec(Set("git *"))`, and the agent is
told the refusal is final rather than being pointed at a `request*` block. Deny patterns
also *extend* across config layers, so a project config can add to the global denylist but
never drop from it. They are listed in `/perms` and in the agent's system prompt.

## The terminal

`/help`, `/model [alias]`, `/models`, `/mode [name]`, `/perms`, `/todos`, `/config`,
`/interface` (print the API the model sees), `/reset` (fresh REPL, conversation kept),
`/clear` (forget the conversation, REPL kept), `/cost`, `/quit`. Ctrl-C interrupts the
current turn including the running snippet, Shift-Tab cycles the mode on an empty prompt,
Ctrl-O toggles the expanded view, Ctrl-D exits.

Every kind of content has its own shape, so a glance tells them apart:

```
> your request

● assistant prose                       bullet + indent, Markdown rendered as it streams

● run_scala                             tool block (magenta)
  │ code the agent runs
  ├ output                              the program's own println output, live
  │ hello
  ├ result   (or  ├ error)              what the REPL added: echoed values, diagnostics
  │ val x: Int = 1
  └ ok 34 ms (or └ failed 34 ms)

  ▸ TODO  ✓ done  ▶ in progress  ○ pending      redrawn once per snippet
  ⚠ Permission request …  /  ? question         pop-ups (list/checkbox menus)
```

Long things are kept short on purpose: streamed reasoning shows its last few lines under
`● thinking…` and collapses to `● thought for 4.2 s · 12 lines`; live program output folds
once it fills 15 terminal rows, leaving a live tail (`⋯ N more lines` + the last 5); echoed
values are cut after 2000 characters, and a long result panel is cut in the middle with each
line trimmed to one row. **Ctrl-O** during a turn switches to the expanded view for the rest
of the session (reasoning in full, nothing folded). Keys typed during a turn are kept as
type-ahead for the next prompt.

The agent's code is syntax-coloured by the Scala compiler's own highlighter, and its prose
is rendered as Markdown while it streams: headings, lists, quotes, rules, `**bold**`,
`` `code` ``, fenced code blocks (coloured when the fence says `scala`, verbatim otherwise)
and pipe tables, drawn aligned once the table is complete. Without a real terminal (`-p` in
a pipe) there are no colours, menus, live windows or folding — everything is printed in
full, and pop-ups fall back to a plain `answer>` line. `ATC_ASCII=1` uses ASCII glyphs.

## Project layout

| Module | What it is |
|--------|------------|
| `lib`  | The one API the model programs against: `atc.lib.Interface` plus the capability and data types (`FileSystem`, `Classified`, `Todo`, …), compiled with capture checking, every agent-visible definition `@assumeSafe`. No implementation lives here; the sandbox injection point (`atc.lib.Runtime`, holding `current` and the root capabilities) sits in its own file, outside the API the model reads. |
| `app`  | The agent program. `atc.host.Host` **implements `Interface` directly** — permission policy, file/process/network effects, questions, TODO list, LLM calls — and the REPL preamble binds that implementation as `api`, so a call in agent code is a plain method call on the host, with no marshalling layer to keep in sync. Also: sandbox and REPL management, LLM providers, terminal UI. |

Built with [Mill](https://mill-build.org) (`./mill`, pinned to 1.1.8) on the same Scala 3
nightly TACIT uses (`3.10.0-RC1-bin-20260816-3adfcbd-NIGHTLY`).

```
lib/src/atc/lib/Interface.scala      the agent-facing API (capabilities, data types, Interface)
                Runtime.scala        the sandbox's injection point (@rejectSafe, not part of the API)
app/src/atc/
  Main.scala                         command line → App
  App.scala                          wiring (config, models, policy, host, sandbox, agent, TUI), slash commands
  Resources.scala, Debug.scala       bundled text resources (version, API source, template); ATC_DEBUG tracing
  agent/   Agent.scala, Prompts.scala          the loop (model ⇄ run_scala), system prompt & tool spec
  config/  Config.scala                        JSON config model, merging, validation, --init template
  host/    Host.scala                          Interface implementation: policy checks + real effects
           Capabilities.scala                  FileSystem/FileEntry/Exec/Network impls (carry a scope id)
           HostPorts.scala                     what the host needs from the app (output, user, LLM)
           Processes.scala, ClassifiedImpl.scala
  llm/     Model.scala                         provider-neutral messages, ChatModel, factory
           AnthropicModel / OpenAIResponsesModel / OpenAIChatModel / EchoModel
           Providers.scala, Json.scala         shared client construction; ujson ↔ SDK values
  perms/   Policy.scala                        rules, scopes (ScopeId), grants, prompts, summary
           PathPattern.scala, Access.scala (Access/Perm/Mode), GlobMatcher.scala
  sandbox/ ReplSession.scala                   the in-process REPL: preamble, compile, run, timeout, interrupt
           Sandbox.scala                       class-loader isolation
           Execution.scala                     ExecutionResult / ExecutionClock / SandboxConfig
           CodeValidator.scala                 regex pre-check of agent code
           CappedRendering.scala               (package dotty.tools.repl) caps echoed values
  ui/      Tui.scala                           JLine terminal: streaming, panels, pop-ups, input
           Markdown.scala                      streaming Markdown → ANSI for the assistant's prose
           Highlight.scala                     Scala colouring via the compiler's SyntaxHighlighting
app/test/src/atc/                    munit suites (TestEnv.scala + ReplAssertions.scala are the shared fixtures)
  CapabilitySuite   the capability type system: read-only vs full, UserIO, escapes, Classified.map
  ModeSuite         the read-only / local / full matrix, and how a mode reaches the sandbox
  SandboxSuite      the sandbox: session, isolation, validator and fatal-throwable safety nets
```

A few behaviours that are easy to miss when reading the code: there is one REPL session per
conversation (definitions persist across turns until `/reset` or a mode switch); a runtime
error in agent code does not fail the REPL, so the session detects uncaught exceptions in
the captured output and reports them to the model as errors, with host stack frames trimmed
and hints appended for common capture-checking stumbles; and the agent loop resumes by
itself when a provider cuts a response after a server-side tool call (web search, Anthropic
`pause_turn`), nudging the model at most twice per turn when it ends a turn on "Let me …"
without acting.

License: Apache-2.0.
