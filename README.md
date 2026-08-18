# ATC — A Minimal Agent With Tracked Capabilities

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
┌───────────── terminal UI (JLine) ─────────────┐
│  you ⇄ agent loop ⇄ LLM (Anthropic / OpenAI)  │
│              │ run_scala(code)  — the only native tool
│   ┌──────────▼───────────┐                              │
│   │ sandbox REPL         │  api.* = atc.lib.Interface   │
│   │  capture checking +  │──────────────────────────────┤
│   │  safe mode           │            ┌─────────────────┴───┐
│   │  (sees only JDK,     │            │ Host extends        │
│   │   scala.*, atc.lib)  │            │   Interface         │
│   └──────────────────────┘            │ policy, pop-ups,    │
│                                       │ files, exec, http,  │
│                                       │ ask/todos, LLMs     │
└───────────────────────────────────────┴─────────────────────┘
```

## Modules

| Module | What it is |
|--------|------------|
| `lib`  | The one API the model programs against: `atc.lib.Interface` plus the capability and data types (`FileSystem`, `Classified`, `Todo`, …), compiled with capture checking, every agent-visible definition `@assumeSafe`. No implementation — the only extra members are on the `Interface` companion: `current` (the installed implementation, read by the preamble) and `takeRootIO()` (the root `IOCap`, once). |
| `app`  | The agent program. `atc.host.Host` **implements `Interface` directly** (permission policy, file/process/network effects, questions to the user, TODO list, LLM calls); the REPL preamble binds that implementation as `api` and imports it, so a call in agent code is a plain method call on the host — no marshalling layer to keep in sync. Also: sandbox/REPL management, LLM providers, terminal UI. |

Everything is built with [Mill](https://mill-build.org) (`./mill`, pinned to 1.1.8) on the
same Scala 3 nightly TACIT uses (`3.10.0-RC1-bin-20260816-3adfcbd-NIGHTLY`).

### Code layout

```
lib/src/atc/lib/Interface.scala      the agent-facing API (capabilities, data types, Interface)
app/src/atc/
  Main.scala                         command line → App
  App.scala                          wiring (config, models, policy, host, sandbox, agent, TUI), slash commands
  agent/   Agent.scala, Prompts.scala          the loop (model ⇄ run_scala), system prompt & tool spec
  config/  Config.scala                        JSON config model, merging, validation, --init template
  host/    Host.scala                          Interface implementation: policy checks + real effects
           Capabilities.scala                  FileSystem/FileEntry/Exec/Network impls (carry a scope id)
           HostPorts.scala                     what the host needs from the app (output, user, LLM)
           Processes.scala, ClassifiedImpl.scala
  llm/     Model.scala                         provider-neutral messages, ChatModel, factory
           AnthropicModel / OpenAIResponsesModel / OpenAIChatModel / EchoModel, Json.scala
  perms/   Policy.scala, PathPattern.scala, Access.scala, GlobMatcher.scala
  sandbox/ ReplSession.scala                   the in-process REPL: compile, run, timeout, interrupt
           Sandbox.scala                       class-loader isolation
           Execution.scala                     ExecutionResult / ExecutionClock / SandboxConfig
           CodeValidator.scala                 regex pre-check of agent code
           CappedRendering.scala               (package dotty.tools.repl) caps echoed values
  ui/      Tui.scala                           JLine terminal: streaming, panels, pop-ups, input
           Markdown.scala                      streaming Markdown → ANSI for the assistant's prose
           Highlight.scala                     Scala colouring via the compiler's SyntaxHighlighting
app/test/src/atc/                    munit suites (see TestEnv.scala for the shared fixture)
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
allows `ls -la` but not `lsblk`). `hosts` are glob patterns on host names.

## How the agent asks for more

The REPL preamble defines

```scala
given io:  IOCap               // the root capability
given fs:  FileSystem^{io}     // configured file permissions (+ session grants)
given ex:  Exec^{io}           // configured commands
given net: Network^{io}        // configured hosts
```

Anything the configuration already permits just works (`read`, `write`, `ls`, `walk`,
`grepRecursive`, `exec("git", List("status"))`, …). If an operation is denied, the
exception says which `request*` block to use:

```scala
requestFiles("/tmp/data", Access.Write, reason = "cache build outputs") {
  write("/tmp/data/out.txt", "done")      // uses the new FileSystem^ given for this block
}
requestExec(Set("npm *"), "install deps") { exec("npm", List("install")) }
requestNetwork(Set("api.github.com"), "check PRs") { httpGet("https://api.github.com/...") }
```

The host shows a pop-up — *Yes, this time* / *Yes, for the rest of this session* / *No* —
and the block runs with the extra permission. `locked` rules cannot be widened at all.
Capture checking guarantees the granted capability (and any `FileEntry` derived from it)
cannot leave the block; the host additionally closes the permission scope when the block
exits, so a scope id can never be reused.

Classified data can be computed on but not looked at: `readClassified(p).map(f)` only
accepts pure `f` (no `io`, `fs`, … captured); the only sinks are `println` (the human sees
the content in the terminal marked `[classified]`, the model gets `Classified(***)`),
`writeClassified`, `chat(Classified)` (safe model), and `httpPostClassified` /
`secretHeaders` to an allow-listed host.

## Sandbox design (how the class loaders and `@assumeSafe` fit together)

* Agent code is compiled by an in-process Scala 3 REPL with capture checking and explicit
  nulls, then `import language.experimental.safe`. Definitions the model may touch are all
  `@assumeSafe` in `atc.lib`; constructors of the capability classes are `private[atc]`
  (agent code lives in the empty package), and the root capability is only obtainable
  through `Interface.takeRootIO()` (guarded so it succeeds once per sandbox — a pure
  function can never obtain an `IOCap`). Regex validation (`CodeValidator`) rejects
  `java.io`, reflection, `caps.unsafe`, `Interface.current`/`takeRootIO`/`install`,
  `atc.host`, … before compilation as defence in depth.
* The REPL loader's parent is a filtering `SandboxLoader` that delegates `scala.*` and
  `atc.lib.*` to the application class loader (so both sides share exactly the same
  classes and the host can implement `Interface` directly) and everything else to the
  platform loader — the JDK. Nothing of the application (`atc.host`, LLM clients, config,
  compiler, JLine, …) is visible. Every capability value carries a host-side *scope id*;
  the host resolves permissions for that scope on each call.
* Only one native LLM tool exists, `run_scala`. Asking the user (`ask`), the TODO list
  (`setTodos`/`markTodo`/`todos`), printing, LLM sub-calls — everything is a Scala function
  in `Interface`, and each one that has an effect requires `IOCap`, so none of them can be
  reached from a pure `Classified.map`.
* Runtime errors in agent code do not fail the REPL, so the executor detects uncaught
  exceptions in the output and reports them to the model as errors; the tool result trims
  host stack frames and appends hints for common capture-checking stumbles.
* The agent loop resumes automatically when a provider cuts a response after server-side
  tool calls (web search, Anthropic `pause_turn`), and nudges the model (at most twice per
  turn) when it ends a turn on "Let me …" without acting.

## Terminal commands

`/help`, `/model [alias]`, `/models`, `/perms`, `/todos`, `/config`,
`/interface`, `/reset` (restart the REPL — a fresh session, all agent-defined vals/defs are
gone), `/clear` (forget the conversation), `/cost`, `/quit`. Ctrl-C interrupts the current
turn (also the running snippet), Ctrl-D exits.

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

## Status and known limits

* Prototype quality; the sandbox is in-process, so a CPU-bound loop that ignores
  interrupts survives the timeout (as in TACIT). `exec` runs real processes with the
  user's privileges — the command allow-list is the boundary.
* Component patterns match anywhere in a path (so `private` would match
  `/private/var/...` on macOS); use `./private` for a project directory.
* Safe mode is experimental; the system prompt documents its current quirks (top-level
  `val`s of capturing types need explicit types, `Option.foreach` needs a pure function,
  no mutable collections at top level). Agent code, like the whole code base, is compiled
  with `-Yexplicit-nulls`, so Java results are `T | Null` and need `.nn` or a null check.
* One sandbox per JVM: the `Interface` companion holds the installed implementation, so two
  live REPL sessions in one process would share it (the app only ever has one).

License: Apache-2.0.
