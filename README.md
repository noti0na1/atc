# ATC — A Minimal Agent With Tracked Capabilities

[![Scala CI](https://github.com/noti0na1/atc/actions/workflows/scala.yml/badge.svg)](https://github.com/noti0na1/atc/actions/workflows/scala.yml)

ATC is a small terminal coding agent (in the spirit of Claude Code) whose **only tool
is a Scala 3 REPL** protected by [capture checking](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/index.html)
and Scala's experimental *safe mode*. Every action the model wants to take — read a
file, run a command, fetch a URL — must be expressed as Scala code against a small,
capability-typed library. The code is compiled first and only executed if it type-checks,
so capabilities cannot be forged, cannot escape their scope, and classified data cannot
leak into impure computations. The ideas come from
[TACIT](https://github.com/lampepfl/tacit) (*Securing Agents With Tracked Capabilities*,
CAIS '26); ATC re-packages them as a self-contained agent instead of an MCP server, with a
redesigned file-permission model and interactive permission requests.

```
  you ⇄ terminal UI ⇄ agent loop ⇄ LLM (Anthropic / OpenAI / compatible)
                          │
                          │  run_scala(code) — the only native tool
                          ▼
  ┌────────────────────────────────┐                              ┌─────────────────────────────┐
  │ sandbox REPL                   │                              │ Host (application side)     │
  │  capture checking + safe mode  │  api.* = atc.lib.Interface   │  implements Interface:      │
  │  agent code sees only the JDK, │ ───────────────────────────▶ │  permission policy, pop-ups │
  │  scala.* and atc.lib           │ ◀─────────────────────────── │  files · exec · http        │
  │                                │ values · output · exceptions │  ask · todos · chat (LLMs)  │
  └────────────────────────────────┘                              └─────────────────────────────┘
```

## Modules

| Module | What it is |
|--------|------------|
| `lib`  | The one API the model programs against: `atc.lib.Interface` plus the capability and data types (`FileSystem`, `Classified`, `Todo`, …), compiled with capture checking, every agent-visible definition `@assumeSafe`. The mode-tracked capabilities (`IOCap`/`FileSystem`/`FileEntry`) use the nightly's [mutable-capability model](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/mutability.html): a bare type is read-only, `^` is full, and writes are `update` methods. There is no implementation here; the sandbox injection point (`atc.lib.Runtime`, with `current` and the root capability labels) sits in its own file, outside the API the model reads. |
| `app`  | The agent program. `atc.host.Host` **implements `Interface` directly** (permission policy, file/process/network effects, questions to the user, TODO list, LLM calls); the REPL preamble binds that implementation as `api` and imports it, so a call in agent code is a plain method call on the host — no marshalling layer to keep in sync. Also: sandbox/REPL management, LLM providers, terminal UI. |

Everything is built with [Mill](https://mill-build.org) (`./mill`, pinned to 1.1.8) on the
same Scala 3 nightly TACIT uses (`3.10.0-RC1-bin-20260816-3adfcbd-NIGHTLY`).

### Code layout

```
lib/src/atc/lib/Interface.scala      the agent-facing API (capabilities, data types, Interface)
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
  sandbox/ ReplSession.scala                   the in-process REPL: compile, run, timeout, interrupt
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

## Quick start

Requires JDK 17+.

```bash
cp .env.example .env              # put ANTHROPIC_API_KEY / OPENAI_API_KEY (and optional ATC_* settings) there
./start.sh -C ~/my-project --init # builds if needed, writes ~/my-project/.atc/config.json (edit models & permissions)
./start.sh -C ~/my-project        # start the agent
```

`start.sh` sources `.env` (without overriding your shell), rebuilds `out/dist.dest/`
with `./mill dist` when sources changed, and passes any flags through to `atc`. Without
the script: `./mill dist` then `out/dist.dest/atc` (or `export` the keys yourself).

For development: `./mill -i app.run -C /some/project` (interactive mode so the terminal
is attached), `./mill app.test`, `./mill lib.compile`, `./mill __.reformat` /
`./mill __.checkFormat` (scalafmt, configured in `.scalafmt.conf`).

Useful flags: `-m <alias>` choose a model, `-p "<request>"` run one turn and exit,
`--approve-all` auto-approve permission requests (for scripted use only), `-c file` add a
config file, `-C dir` set the working directory.

## Configuration

Config files are JSON and merged in this order: `~/.config/atc/config.json` (global),
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
  "hosts": ["*.scala-lang.org", "docs.oracle.com"],
  "safeMode": true,
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
* `openai-responses` — Responses API; also works with other vendors that implement it
  (e.g. DeepSeek: `"baseUrl": "https://api.deepseek.com"`). `webSearch: true` adds the
  built-in `web_search` tool. `reasoning` maps to `reasoning.effort`; `"reasoningSummary":
  "auto"` asks OpenAI to stream reasoning summaries (shown as thinking; DeepSeek streams its
  reasoning without it).
* `openai` — Chat Completions; also for any OpenAI-compatible server (Ollama, vLLM, LM
  Studio, OpenRouter…) via `baseUrl`. `webSearch: true` sets `web_search_options` (only
  search-enabled models accept it).

API keys: `apiKey` (a literal or `"${SOME_ENV_VAR}"`), `apiKeyEnv`, or the SDK's own
resolution (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, …).

Two roles: **`model`** is the agent (it never sees classified data); **`safeModel`** is the
model that handles `Classified` values through the library's `chat(Classified[String])`.
Point it at something you trust with your secrets (typically a local model). The agent
model can be switched at runtime with `/model <alias>` (conversation history is
provider-neutral, so mid-conversation is fine); the safe model is fixed by the config.

### File permissions

Each rule has a `path` pattern and any of `access` (`none|read|write`), `classified`
(bool), `locked` (bool). Patterns are gitignore-flavoured, as in TACIT:

* no `/` in the pattern → matches any path **component** anywhere (`.env`, `*.pem`,
  `node_modules`) — use `./name` for a project-relative directory;
* relative with `/` → relative to the working directory, with `*`, `**`, `?`, `[…]`;
* absolute or `~/…` → absolute; `.` is the working directory itself.

A rule applies to the path it matches **and its whole subtree**. The effective access of a
path is the **minimum** over all matching rules (a path matched by no rule with an access
level is inaccessible), it is **classified** if any matching rule says so, and **locked** if
any does. So a sub-folder inherits its parent's permission and can only be made stricter —
`build/generated: write` under `build: read` still yields `read`. If `files` is empty the
working directory is writable; the built-in classified patterns (`.ssh`, `.gnupg`, `.env`,
`.env.*`, `.netrc`, `.npmrc`, `.pypirc`, `.docker`, `.kube`, `.aws`, `.azure`, `.gcloud`,
`*.pem`, `id_rsa`, `id_ed25519`) are always added unless `"defaultClassified": false`.

Classified means: content is only observable as `Classified[String]`; a classified
**directory's structure is classified too** — the directory itself is visible in its
parent, but listing it needs `childrenClassified`/`walkClassified` (returning
`Classified[List[String]]`); `walk`/`grepRecursive`/`find` do not descend into it.
Plain `write` on a classified path is refused (use `writeClassified`), and
`writeClassified` on a *non*-classified path is refused too (it would declassify).
Symlinks are judged by their target.

`commands` are patterns over the full command line: `*` is a wildcard and a pattern
without `*` matches by word prefix (`"git status"` allows `git status --short`; `"ls"`
allows `ls -la` but not `lsblk`). A command also needs read access to the directory it
runs in (the working directory by default), which must not be classified — checked
through the `FileSystem` capability, so a `requestFiles` block covers it. `hosts` are
glob patterns on host names; only `http`/`https` URLs are accepted.

## Modes: read-only, local, full

A **mode** decides what the sandbox can do at all, independent of the per-path permission
policy. Every mode gives the agent `given io` and `given fs`, but with different power:

| Mode | `given`s (besides the always-full `user: UserIO^`) | Can |
|------|----------------------------------------------------|-----|
| **read-only** | `io: IOCap` (read-only), `fs: FileSystem^{io.rd}` | read files only |
| **local** | `io: IOCap` (read-only), `fs: FileSystem^`, `ex: Exec^` | read/write files, run commands |
| **full** | `io: IOCap^`, `fs: FileSystem^{io}`, `ex`, `net` | files, commands, network |

Talking to the user is a **separate capability**, `user: UserIO^`, which is always
present and always full, so `println`, `ask`, the TODO list and `chat` work in every
mode (you can always report results), while `io` alone decides what you may do to the
file system, processes and network.

The distinction is enforced *by the types* (following the nightly's
[mutable-capability model](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/mutability.html)):
a bare capability type (`FileSystem`, `IOCap`) is the **read-only** view, `^` / `^{io}` the
**full** view. The write operations (`write`, `append`, `delete`, `mkdir`, `writeClassified`)
are `update` methods, callable only through a full capability, so in read-only mode `write`
simply does not compile ("*cannot subsume a read-only capture set*" / "*Cannot call update
method*"), and `Exec`/`Network` are derived only from a full `IOCap^`, which read-only and
local modes do not hand out. The permission `Policy` *also* enforces the mode at run time
(writes downgraded to read, `exec`/network refused), so nothing depends on the type check
alone.

Switch modes with **`/mode`** (cycles read-only → local → full), **Shift-Tab** (same, on an
empty prompt), `/mode <name>`, the `--mode` flag, or `"mode"` in the config. Switching starts
a fresh REPL (agent-defined `val`s/`def`s are gone) but keeps the conversation. The default
is full.

## How the agent asks for more

The REPL preamble defines the capabilities of the current mode, e.g. in full mode:

```scala
given io:  IOCap^              // the root capability
given fs:  FileSystem^{io}     // configured file permissions (+ session grants)
given ex:  Exec^{io}           // configured commands
given net: Network^{io}        // configured hosts
```

A helper that writes must ask for a full file system (`(using fs: FileSystem^)`); a bare
`FileSystem` can only read, and `readOnlyFileSystem` gives an explicitly read-only one.

Anything the configuration already permits just works (`read`, `write`, `ls`, `walk`,
`grepRecursive`, `exec("git", List("status"))`, …). If an operation is denied, the
exception says which `request*` block to use:

```scala
requestFiles("/tmp/data", Access.Write, reason = "cache build outputs") {
  write("/tmp/data/out.txt", "done")      // uses the new FileSystem^ given for this block
}
requestFiles("~/notes", reason = "look up the design notes") {   // read-only fs in read-only mode
  read("~/notes/design.md")
}
requestExec(Set("npm *"), "install deps") { exec("npm", List("install")) }
requestNetwork(Set("api.github.com"), "check PRs") { httpGet("https://api.github.com/...") }
```

`requestFiles` works in every mode: the file system it hands the block is exactly as
capable as the one you already have: full (so you can write) in local/full mode,
read-only in read-only mode.

The host shows a pop-up — *Yes, this time* / *Yes, for the rest of this session* / *No* —
and the block runs with the extra permission. `locked` rules cannot be widened at all.
Capture checking guarantees the granted capability (and any `FileEntry` derived from it)
cannot leave the block; the host additionally closes the permission scope when the block
exits, so a scope id can never be reused.

Classified data can be computed on but not looked at: `readClassified(p).map(f)` accepts
an `f` that captures only **read-only** capabilities (`->{any.rd}`), so it can compute and
read but never write, run a command, use the network, print, `chat` or `ask`, because each
of those needs a *full* capability. The only sinks are `println` (the human sees
the content in the terminal marked `[classified]`, the model gets `Classified(***)`),
`writeClassified`, `chat(Classified)` (safe model), and `httpPostClassified` /
`secretHeaders` to an allow-listed host.

## Sandbox design (how the class loaders and `@assumeSafe` fit together)

* Agent code is compiled by an in-process Scala 3 REPL with capture checking and explicit
  nulls, then `import language.experimental.safe`. Definitions the model may touch are all
  `@assumeSafe` in `atc.lib`; constructors of the capability classes are `private[atc]`
  (agent code lives in the empty package), and the root capability `Interface.rootIO` is
  `@rejectSafe`, so agent code (compiled under safe mode) cannot name it, and a pure
  function can never obtain an `IOCap`. Regex validation (`CodeValidator`) rejects
  `java.io`, reflection, `caps.unsafe`, `Interface.current`/`rootIO`/`install`,
  `atc.host`, … before compilation as defence in depth.
* The REPL loader's parent is a filtering `SandboxLoader` that delegates `scala.*` and
  `atc.lib.*` to the application class loader (so both sides share exactly the same
  classes and the host can implement `Interface` directly) and everything else to the
  platform loader — the JDK. Nothing of the application (`atc.host`, LLM clients, config,
  compiler, JLine, …) is visible. Every capability value carries a host-side *scope id*;
  the host resolves permissions for that scope on each call.
* Only one native LLM tool exists, `run_scala`. Asking the user (`ask`), the TODO list
  (`setTodos`/`markTodo`/`todos`), printing, LLM sub-calls — everything is a Scala function
  in `Interface`, and each one that has an effect requires `IOCap` (or a derived capability),
  so none of them can be reached from a pure `Classified.map` (whose function is typed
  `T ->{any.rd} B`: it may capture read-only capabilities but no full one, and every
  outward channel needs a full capability).
* Runtime errors in agent code do not fail the REPL, so the executor detects uncaught
  exceptions in the output and reports them to the model as errors; the tool result trims
  host stack frames and appends hints for common capture-checking stumbles.
* The agent loop resumes automatically when a provider cuts a response after server-side
  tool calls (web search, Anthropic `pause_turn`), and nudges the model (at most twice per
  turn) when it ends a turn on "Let me …" without acting.

## Terminal commands

`/help`, `/model [alias]`, `/models`, `/mode [name]` (cycle or set the sandbox mode, starting
a fresh REPL with the conversation kept), `/perms`, `/todos`, `/config`, `/interface`,
`/reset` (restart the REPL: a fresh session, all agent-defined vals/defs are gone), `/clear` (forget the
conversation), `/cost`, `/quit`. Ctrl-C interrupts the current turn (also the running
snippet), Shift-Tab cycles the mode on an empty prompt, Ctrl-D exits.

There is one REPL session per conversation: definitions the agent makes in one snippet are
available in the next, across turns, until you `/reset`.

Every kind of content has its own shape on screen, so a glance tells them apart:

```
> your request

● assistant prose                       bullet + indent

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

When the model streams its reasoning (Anthropic thinking, OpenAI/DeepSeek reasoning), the last
few lines are shown live under `● thinking…` and collapse to `● thought for 4.2 s · 12 lines`
when the answer starts; live program output longer than 15 lines is folded to a live tail
(`⋯ N more lines` + the last 5). **Ctrl-O** during a turn toggles the expanded view for the
session (thinking streamed in full, nothing folded). Keys typed during a turn are kept as
type-ahead for the next prompt.

The agent's code is syntax-coloured (by the Scala compiler's own highlighter) and its prose
is rendered as Markdown while it streams: headings, lists, quotes, rules, `**bold**`,
`` `code` ``, fenced code blocks (coloured only when the fence is tagged `scala`; other
languages and untagged blocks are shown verbatim) and pipe tables (drawn aligned once the
table is complete, honouring `:--`/`--:`/`:-:` alignment).
Echoed values are cut after 2000 characters and long result sections in the middle. Pop-ups
fall back to a plain `answer>` line when there is no real terminal (e.g. `-p` in a pipe;
no colours, Markdown rendering, live windows or folding there — everything is printed in
full). Set `ATC_ASCII=1` to draw the layout with ASCII characters only.

## Testing without an API key

`"provider": "echo"` is a built-in model that echoes your text and turns `run: <code>`
into a `run_scala` call — handy for trying the sandbox and the permission flow:

```bash
cat > .atc/config.json <<'EOF'
{ "model": "echo", "safeModel": "echo", "models": { "echo": { "provider": "echo", "model": "echo" } } }
EOF
atc -p 'run: println(read("README.md").take(80))'
```

License: Apache-2.0.
