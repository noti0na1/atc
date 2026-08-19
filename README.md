# ATC: A Minimal Agent With Tracked Capabilities

[![Scala CI](https://github.com/noti0na1/atc/actions/workflows/scala.yml/badge.svg)](https://github.com/noti0na1/atc/actions/workflows/scala.yml)

ATC (Agent with Tracked Capabilities) is a minimal terminal coding agent whose **only tool is a
Scala 3 REPL** protected by [capture checking](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/index.html) and [safe mode](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/safe.html). Every action the model wants to take (read a file,
run a command, fetch a URL) has to be written as Scala against a small, capability-typed
library, and that code is compiled before it is allowed to run.

What the agent may do is therefore decided by **which capabilities are in scope**, and an
attempt to exceed them is a compile error rather than a runtime check somebody had to
remember to write. The ideas come from [TACIT](https://github.com/lampepfl/tacit)
(*Securing Agents With Tracked Capabilities*, CAIS '26); ATC re-packages them as a
self-contained agent instead of an MCP server, with a redesigned file-permission model and
interactive permission requests.

This page is the user's guide: how to install and drive ATC, and the idea that makes it
safe. Everything under the hood (architecture, the configuration semantics in depth, the
sandbox, the terminal internals, building, testing, releases) is in
[doc/development.md](doc/development.md).

## What it looks like

A request, the Scala the agent wrote for it, what the program printed, and the answer:

```scala
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
The snippet was compiled before it ran. The REPL keeps its state between snippets, so a
`val` defined in one turn is still there in the next.

The same agent in **read-only mode**, asked to change a file, cannot even express the
write: the sandbox hands it a read-only file system, and `write` is a call to an `update`
method through it. The compiler says no, and the agent says why:

```scala
read-only > add a "review the tests" item to TODO.md

● run_scala
  │ append("TODO.md", "- review the tests\n")
  ├ error
  │ Found:    (fs : atc.lib.FileSystem^{io.rd})
  │ Required: atc.lib.FileSystem^{any}
  │ … it cannot subsume a read-only capture set of the stateful type
  │   (fs : atc.lib.FileSystem^{io.rd}).
  └ failed 84 ms

● I am in read-only mode, so I cannot write TODO.md. Switch to local mode (/mode local
  or Shift-Tab) and I will add the line.
```

And when the agent needs something the configuration does not grant, it asks for exactly
that, through a block whose extra permission cannot leak out of it; you decide at a pop-up:

```scala
> install the dependencies and run the tests

● run_scala
  │ requestExec(Set("npm *"), "install dependencies and run the test suite") {
  │   println(exec("npm", List("install")).stdout.takeRight(400))
  │   println(exec("npm", List("test")).stdout.takeRight(2000))
  │ }

  ⚠ Permission request: Run commands
    patterns: npm *
    reason:   install dependencies and run the test suite
    Allow?
    › Yes, this time
      Yes, for the rest of this session
      No
```

## Setup

You need JDK 17 or newer. The `atc` wrapper script downloads the jars of the latest
[GitHub release](https://github.com/noti0na1/atc/releases), checks them against the
digests GitHub records, and runs them:

```bash
curl -fsSL https://raw.githubusercontent.com/noti0na1/atc/refs/heads/main/atc -o atc
chmod +x atc
./atc setup      # installs ~/.local/bin/atc, puts it on PATH, downloads the latest release
```

From then on `atc` runs ATC in the current directory, `atc update` fetches a newer release,
`atc self update` refreshes the wrapper, `atc self uninstall` removes both, and `atc help`
lists the wrapper's commands. The jars live in `~/.atc/jars/`, beside the global config.
(To run from a checkout instead, see [doc/development.md](doc/development.md#building-and-running).)

**1. Start it.** Change to the project you want to work on, then run `atc`:

```bash
cd ~/my-project
atc
```

The first run finds no `~/.atc/config.json` and offers to write the starting one
(providers, machine-wide permissions) together with `~/.atc/keys.properties` beside it,
then exits so you can fill in the keys you use:

```properties
ANTHROPIC_API_KEY=sk-ant-…
OPENAI_API_KEY=
```

Alternatively export the variables in your shell, or use a local model that needs no key.
Answer no and nothing is written: the built-in starting config is used for that run
(`atc --init-global` writes it later, on demand).

**2. Start it again.** With the keys in place, run `atc` again. Started in a directory no
config grants, atc offers to write a starting `.atc/config.json` there and uses it at
once, so you land in the prompt with the project open:

```bash
atc
```

That file opens the project to the agent: without it (or a rule in `~/.atc/config.json`)
nothing is readable and the agent has to ask for every file. Open it when you like and
decide two things: which **models** to use, and which **files, commands and hosts** the
agent may touch without asking. Both have working defaults; the permissions are the part
worth reading; see [Configuration](#configuration). (`atc --init` writes the same file
without asking, for scripts.)

**3. Talk to it.** Type a request at the prompt; the agent answers by writing and running
Scala in the sandbox, and asks before touching anything the config does not grant.
`/help` lists the slash commands (`/model`, `/mode`, `/cost`, `/new`, …), Ctrl-C
interrupts a turn, Ctrl-D quits, and `-p "<request>"` runs a single turn from the shell
instead:

```bash
atc -p 'summarise the README'
```

Useful flags: `-m <alias>` pick a model, `--mode readonly|local|full` pick a sandbox mode,
`-p "<request>"` run one turn and exit, `-c <file>` add a config file, `-C <dir>` set the
working directory, `--approve-all` auto-approve permission requests (scripted use only);
`atc run --help` lists them all (`atc --help` describes the wrapper).

## The idea: capabilities instead of ambient authority

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
a new one: the capability classes cannot be constructed by agent code, the only instances
are the ones the REPL preamble binds, and the object holding the roots is off limits under
safe mode.

### Two roots

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
| `IOCap` | nothing by itself; it is the root the others are derived from | the preamble (`given io`) |
| `FileSystem` | `read`, `ls`, `walk`, `grep`, …; `write`, `append`, `delete`, `mkdir` need a full one | `fileSystem(using io: IOCap^)`, or the preamble's `fs` |
| `FileEntry` | a handle to one file or directory; as capable as the `FileSystem` it came from | `fs.access(path)` |
| `Exec` | running commands | `processes(using io: IOCap^)` |
| `Network` | HTTP requests | `network(using io: IOCap^)` |
| `UserIO` | printing, questions, the TODO list, `chat` with the normal model | the preamble (`given user`), always full |

`UserIO` is deliberately *not* derived from `IOCap`: reporting to the human is an effect on
the conversation, not on the machine, so it survives even when the agent may touch nothing
at all.

### Read-only and full views

Each capability type has two views, following the nightly's
[mutable-capability model](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/mutability.html):
the **bare** type (`FileSystem`, `IOCap`) is the **read-only** view; `^`, or `^{io}` ("as
capable as `io`"), is the **full** view. The mutating operations are declared `update def`
in the library, and an `update` method can only be called through a full capture set. That
one rule is what turns "read-only" from a runtime check into a typing rule:

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

It propagates into your own helpers, so a `def` that writes has to say so in its signature
(`def save(path: String, text: String)(using fs: FileSystem^): Unit`).

### Capabilities cannot escape

Because capture checking tracks capabilities in *types*, the compiler knows which values
hold which capability and refuses the ones that would outlive their scope. That is what
makes [`request*` blocks](#asking-for-more) safe: the wider file system lent to a block
cannot be stashed in a `val` that survives it, returned from it, or captured by a closure
that escapes it.

### Classified data

Capabilities constrain *effects*. Confidential content gets a second, independent
discipline: `readClassified(path)` gives a `Classified[String]`, whose `map`/`flatMap` take
a function that may capture **read-only** capabilities only (`T ->{any.rd} B`). Every
outward channel needs a *full* one (`println`/`ask`/`chat` need `UserIO^`, `write` needs
`FileSystem^`, `exec` needs `Exec`, `httpGet` needs `Network`), so none of them can appear
inside a `map`. The agent can compute on a secret but never see it; `toString` is
`Classified(***)`. The ways out are deliberate and few: `println` (you see the value in the
terminal, marked `[classified]`; the model sees `Classified(***)`), `writeClassified` into a
classified path, `chat(Classified)` with the configured classified model, and
`httpPostClassified` / `secretHeaders` to an allow-listed host.

### What the types do not know

Types decide what compiles; they know nothing about your configuration. So every host method
*also* checks the permission policy for the path, command or host in question, and a
capability from a `request*` block is refused once that block has closed. Underneath sit a
validator that rejects the obvious escape hatches (`java.io`, reflection, the application's
own packages) before compilation, and a class loader that shows agent code only the JDK,
`scala.*` and the agent library. The details are in
[doc/development.md](doc/development.md#defence-in-depth).

## Modes: read-only, local, full

A **mode** decides which capabilities the preamble puts in scope, and therefore what the
agent can express at all, before the permission policy even comes up.

| Mode | In scope | The agent can |
|---|---|---|
| **read-only** | `io: IOCap` (read-only), `fs: FileSystem^{io.rd}`, `user: UserIO^` | read files, report, ask |
| **local** | `io: IOCap` (read-only), `fs: FileSystem^`, `ex: Exec^`, `user: UserIO^` | also write files and run commands |
| **full** | `io: IOCap^`, `fs: FileSystem^{io}`, `ex: Exec^{io}`, `net: Network^{io}`, `user: UserIO^` | also reach the network |

In read-only mode a write is an `update` call through a read-only view (the error shown
[above](#what-it-looks-like)); in local mode there is no full `IOCap` to derive a `Network`
from, so a network call simply has no given to resolve. Either way a mode can withdraw the
machine and leave the conversation intact, so the agent can always say what it *would* have
done; the system prompt tells it to do exactly that rather than look for a way around a
mode. The policy enforces the same three levels again at run time, so nothing rests on the
type check alone.

Switch modes with `/mode` (cycles read-only → local → full), **Shift-Tab** on an empty
prompt, `/mode <name>`, the `--mode` flag, or `"mode"` in the config. Switching starts a
fresh REPL (the agent's `val`s and `def`s are gone) but keeps the conversation. The
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

You get a pop-up (*Yes, this time* / *Yes, for the rest of this session* / *No*) and the
block runs with the extra permission. `locked` rules cannot be widened at all, and a
`denyCommands`/`denyHosts` match is refused without a pop-up. The granted capability cannot
leave the block (capture checking), and the host closes the permission scope when the block
exits. `requestFiles` works in every mode: the file system it lends the block is exactly as
capable as the one you already hold, so the same call site compiles everywhere.

## Configuration

Config files are JSON, and there are three layers:

| | layer | file | may |
|---|---|---|---|
| 1 | global | `~/.atc/config.json` | grant anything |
| 2 | project | the nearest `.atc/config.json` at or above the working directory | open **its own project** (files inside its folder, commands, hosts); narrow anything |
| 3 | explicit | `-c <file>` | grant anything |

**`~/.atc/config.json` is the base, and there is nothing behind it.** No policy is compiled
into the program: what no config grants is not permitted. The
[starting config](app/resources/atc/config-template.json) written on the first run protects
without granting: it lists the providers, classifies the usual credential paths, puts `.atc`
itself out of reach, refuses `rm -rf *` and `sudo *`, and grants no file, no command and no
host. Edit it to grant things machine-wide.

**A directory is workable because a config says so.** The
[project config](app/resources/atc/project-template.json) that `atc --init` writes (or the
first run in a directory offers) opens the project: its own tree (with `./.git` read-only
and `./secrets` classified), the read-only git commands, and a set of documentation hosts.
The project layer is found by walking up from the working directory, the way git finds
`.git`, and its relative patterns are read against the folder holding `.atc`.

A project's config ships inside the repository you are working in, so it may open *that
repository* but nothing beyond it, and never past a limit the machine's owner set: its
file rules grant only inside its own folder and otherwise only narrow; `commands` and
`hosts` are the union of every layer (the one place a project reaches beyond its tree,
with the deny lists as the backstop); `denyCommands`/`denyHosts` accumulate and nothing can
drop one; the scalar limits (`mode`, `safeMode`, `maxToolCalls`, `executionTimeoutMs`, …)
only move towards the stricter value. Non-permission settings (`model`, `providers`,
`instructions`, …) merge in layer order, later wins. What *you* grant at a pop-up is not
narrowed by any layer: the human is the authority. The exact rules are in
[doc/development.md](doc/development.md#configuration-semantics).

```json
{
  "model": "claude",
  "classifiedModel": "local",
  "providers": {
    "anthropic": {
      "api": "anthropic",
      "models": {
        "claude": { "name": "claude-opus-5",   "webSearch": true, "reasoning": "high", "contextWindow": "200k" },
        "sonnet": { "name": "claude-sonnet-5", "webSearch": true, "contextWindow": "200k" }
      }
    },
    "openai": {
      "api": "openai-responses",
      "models": { "gpt": { "name": "gpt-5", "webSearch": true, "contextWindow": "400k" } }
    },
    "ollama": {
      "api": "openai",
      "url": "http://localhost:11434/v1",
      "key": "ollama",
      "models": { "local": { "name": "llama3.1" } }
    }
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
  "predictInput": true,
  "instructions": "Use 2-space indentation."
}
```

### Providers and models

A **provider** is one endpoint: an `api` (the wire protocol), an optional `url` and key, and
the `models` reachable through it. A **model** is an alias under that provider, whose `name`
is the id the provider knows it by (`name` defaults to the alias, so `"models": { "gpt-5":
{} }` is enough) plus its own settings: `webSearch`, `reasoning`, `thinking`,
`reasoningSummary`, `maxTokens`, `contextWindow`, `temperature`, `webSearchVersion`.
A model is named by its **alias** (`"model": "claude"`, `/model sonnet`), or by
**`provider/alias`** when two providers use the same alias; `/models` lists them.

`api` is one of

* `anthropic`: Messages API. `webSearch: true` adds the server-side web search tool;
  adaptive thinking is on unless `"thinking": false`; `reasoning` is the effort
  (`low|medium|high|xhigh|max`).
* `openai-responses`: Responses API; also works with other vendors that implement it (e.g.
  DeepSeek via `url`). `webSearch: true` adds the built-in web search; `reasoning` is the
  effort; `"reasoningSummary": "auto"` streams reasoning summaries.
* `openai`: Chat Completions; the adapter for any OpenAI-compatible server (Ollama, vLLM,
  LM Studio, OpenRouter, …) via `url`. For both OpenAI-shaped adapters `"thinking":
  true|false` sends the vendor thinking switch of DeepSeek/GLM/Kimi/MiniMax; leave it unset
  for OpenAI itself.
* `echo`: a key-less local model for smoke tests.

`contextWindow` is the model's limit in tokens (`200000`, `"256k"`, `"1m"`). When the
conversation would no longer fit, the oldest exchanges are dropped before the next request,
the model is told, and the terminal warns you; leave it at the model's real window.

**API keys** never sit in a config. A provider names the variable that holds its key
(`"key": "${DEEPSEEK_API_KEY}"`), and the values live in `.atc/keys.properties`, one
`NAME=value` per line, looked up in the project's `.atc/keys.properties`, then
`~/.atc/keys.properties`, then the environment (an empty value falls through to the next
source). `atc --init` adds a `.atc/.gitignore` for it, and the starting policy makes `.atc`
unreadable and `locked`, so the agent can read neither the keys nor the config. `/config`
lists which variables are bound and from where, never their values.

Two roles: **`model`** is the agent and never sees classified data; **`classifiedModel`**
handles `Classified` values through `chat(Classified[String])`, so point it at something you
trust with your secrets, typically a local model, or leave it unset. `/model [ref]` and
`/classifiedmodel [ref]` switch them for the session (without an argument they open a
pick-list; `off` unsets the classified one); the conversation survives a switch, and a
project config in the working directory remembers the choice.

### File permissions, commands and hosts

Each file rule has a `path` pattern and any of `access` (`none|read|write`), `classified`
and `locked`. Patterns are gitignore-flavoured: no `/` in the pattern matches a path
**component** anywhere (`.env`, `*.pem`, `node_modules`); relative with `/` is relative to
the working directory (to the project folder in a project config), with `*`, `**`, `?`,
`[…]`; absolute and `~/…` are absolute; `.` is the working directory itself. A rule applies
to the path it matches **and its whole subtree**; the effective access of a path is the
**minimum** over all matching rules (no matching rule, no access), it is classified or
locked if any matching rule says so, and a deeper rule can only make things stricter.

**Classified** content is only observable as `Classified[String]`, and a classified
directory's structure is classified too (listing it needs `childrenClassified`/`walkClassified`;
`walk`/`grepRecursive`/`find` do not descend into it). A plain `write` to a classified path
is refused, and so is `writeClassified` to a non-classified path. **Locked** means no prompt
can widen the rule. `"respectGitignore": true` (the default) additionally hides what git
ignores from listings; that is visibility, not permission, so an ignored file is still
readable by name.

`commands` are patterns over the whole command line: `*` is a wildcard, and a pattern
without `*` matches by word prefix (`"git status"` allows `git status --short` but not
`git statusx`). A command also needs read access to the directory it runs in. A pre-approved
command runs with your privileges and is *not* subject to the file rules, so pre-approve the
subcommands you mean rather than `git *`. `hosts` are glob patterns on host names; only
`http`/`https` URLs are accepted and redirects are not followed. `denyCommands` and
`denyHosts` are the denylists, same syntax: **deny wins over every allow**, over a session
grant, over an open `request*` scope and over `--approve-all`.

## The terminal

Slash commands: `/help`, `/model [ref]`, `/classifiedmodel [ref]`, `/models`, `/mode [name]`,
`/perms`, `/todos`, `/config`, `/interface` (print the API the model sees), `/run [code]`
(run Scala in the sandbox yourself, with the same API, givens and permissions as the agent;
the agent is told what you ran on its next turn, since the REPL is shared), `/new` (start
over: fresh REPL, conversation, TODO list and session grants forgotten), `/reset` (fresh
REPL, conversation kept), `/clear` (forget the conversation, REPL kept), `/cost` (token
usage and how full the context is), `/quit`. Ctrl-C interrupts the current turn including
the running snippet, Shift-Tab cycles the mode on an empty prompt, Ctrl-O toggles the
expanded view, Ctrl-D exits. Tab completes a slash command and its argument.

Multi-line input: Shift+Enter or Alt+Enter inserts a newline where the terminal reports
those keys (kitty, Ghostty, WezTerm, foot, or an iTerm2/VS Code set up to send `\`+Enter for
Shift+Enter, as Claude Code's terminal setup does), and so does a `\` typed at the end of
the line before Enter; a pasted block keeps its newlines; a `/run` continues while a
bracket, string or comment is still open, indented like a REPL; and a bare `/run` reads a
whole block. In every case Enter on an empty line submits and Ctrl-C clears.

After each turn the agent model is asked to guess your next request, shown as faint ghost
text at the prompt: Tab or → accepts it, typing anything else replaces it. It is one small
extra model call per turn (`"predictInput": false` turns it off), never made with the
classified model, and counted in `/cost`. The summary line after each turn (`● worked for
3 s · 2 tool calls · 1.2k tokens · context 45.2k/200k (23%)`) also shows how full the
model's context is.

Every kind of content has its own shape, so a glance tells them apart:

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

Long things are kept short on purpose: streamed reasoning shows its last few lines and
collapses to `● thought for 4.2 s · 12 lines`, live program output folds into a live tail
once it fills the screen, and long result panels are cut in the middle; **Ctrl-O** switches
to the expanded view (nothing folded) for the rest of the session. Without a real terminal
(`-p` in a pipe) there are no colours, menus or folding; everything is printed in full.
`ATC_ASCII=1` uses ASCII glyphs, `ATC_DEBUG=1` prints stack traces.

## License

Apache-2.0.
