# ATC: A Minimal Agent With Tracked Capabilities

[![Scala CI](https://github.com/noti0na1/atc/actions/workflows/scala.yml/badge.svg)](https://github.com/noti0na1/atc/actions/workflows/scala.yml)

ATC (Agent with Tracked Capabilities) is **a terminal coding agent that, by construction,
cannot exceed the permissions you grant it**.

Its only tool is a Scala 3 REPL. Every action the model takes: reading a file, running a
command, or fetching a URL, is expressed as Scala code against a small, capability-typed
library. The code is compiled before it runs, and its effects are tracked in the type
system through
[capture checking](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/index.html)
and [safe mode](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/safe.html):
a capability calculus with a formal metatheory. The design comes from
[TACIT](https://github.com/lampepfl/tacit) (*Securing Agents With Tracked Capabilities*,
CAIS '26). ATC repackages that design as a self-contained terminal agent with improved
interactivity and more practical permission controls.

**Safety and privacy first:**

- **Effects are tracked, not trusted.** The compiler tracks file, command, network, and
  secret access as capabilities. Exceeding them is therefore a compile error caught before
  any code runs.
- **Privacy is typed.** Secrets become `Classified` values. The model can compute with them
  but cannot read them or send them to a channel you have not authorized.
- **You control every permission.** ATC applies a layered, deny-by-default policy to files,
  commands, and hosts, backed by deny lists that no other rule can override.
- **Fewer interruptions.** Grant routine access once, and ATC asks only for the remaining
  permissions. Each extra permission is confined to a scope from which it cannot escape.
- **Extensible.** A new tool is just a method on the library, inheriting the same tracking,
  permission checks, and classified-data discipline.

## What it looks like

Here is a request, the Scala code the agent wrote, the program output, and the final answer:

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

When asked to change a file in **read-only mode**, the same agent cannot even express the
write. The sandbox provides a read-only file system, while `write` is an `update` method
that requires a full view. The compiler rejects the call, and the agent explains why:

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

When the agent needs access that the configuration does not grant, it requests exactly that
access within a block from which the extra permission cannot escape. You decide whether to
approve the request in a pop-up:

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

If the first run finds no `~/.atc/config.json`, it offers to create a starter file containing
providers and machine-wide permissions. It also creates `~/.atc/keys.properties`, then
exits so you can add the keys you use:

```properties
ANTHROPIC_API_KEY=sk-ant-…
OPENAI_API_KEY=
```

Alternatively, export the variables in your shell or use a local model that needs no key.
If you decline, nothing is written; ATC uses the built-in starter config for that run
(`atc --init-global` writes it later, on demand).

**2. Start it again.** With the keys in place, run `atc` again. If no configuration grants
access to the current directory, ATC offers to create a starter `.atc/config.json` there
and applies it immediately, so the project is open when the prompt appears:

```bash
atc
```

That file gives the agent access to the project. Without it—or a matching rule in
`~/.atc/config.json`—nothing is readable, and the agent must request access to every file.
Review the file to choose which **models** to use and which **files, commands, and hosts**
the agent may access without asking. The defaults work as written, but you should review
the permissions; see [Configuration](#configuration). For scripts, `atc --init` writes the
same file without prompting.

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
def exec(command: String, args: Seq[String])(using Exec, FileSystem): ProcessResult
def httpGet(url: String)(using Network): String
def println(x: Any)(using UserIO^): Unit
```

A snippet can therefore perform only the operations allowed by the givens in its scope. It
cannot create new capabilities: agent code cannot construct the capability classes, the
REPL preamble binds the only instances, and safe mode prevents access to the object that
holds the roots.

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
| `FileSystem` | `read`, `ls`, `walk`, `grep`, …; `write`, `append`, `delete`, `mkdir` need a full one | the preamble's `fs` (derived from `io` by the sandbox; `val ro: FileSystem^{fs.rd} = fs` is a read-only view) |
| `FileEntry` | a handle to one file or directory; as capable as the `FileSystem` it came from | `access(path)` |
| `Exec` | running commands | the preamble's `ex` (local and full mode) |
| `Network` | HTTP requests | the preamble's `net` (full mode) |
| `UserIO` | printing, questions, the TODO list, `chat` with the normal model | the preamble (`given user`), always full |

`UserIO` is deliberately *not* derived from `IOCap`: reporting to the human is an effect on
the conversation, not on the machine, so it survives even when the agent may touch nothing
at all.

### Read-only and full views

Each capability type has two views, following the nightly compiler's
[mutable-capability model](https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/mutability.html):
the **bare** type (`FileSystem`, `IOCap`) is the **read-only** view; `^`, or `^{io}` ("as
capable as `io`"), is the **full** view. The mutating operations are declared `update def`
in the library, and an `update` method can only be called through a full capture set. This
rule turns "read-only" from a runtime check into a typing rule:

```scala
val e: FileEntry^{fs} = fs.access("notes.md")   // as capable as `fs` itself
e.read()                                       // fine through either view
e.write("hello")                               // only if `fs` is the full view
```

With a read-only `fs`, the compiler rejects the final line directly:

```
Cannot call update method write of e
since its capture set {e} is read-only.
```

The restriction also propagates into your own helpers, so a `def` that writes must declare
the requirement in its signature
(`def save(path: String, text: String)(using fs: FileSystem^): Unit`).

### Capabilities cannot escape

Because capture checking tracks capabilities in *types*, the compiler knows which values
hold which capability and refuses the ones that would outlive their scope. That is what
makes [`request*` blocks](#asking-for-more) safe: the wider file system lent to a block
cannot be stashed in a `val` that survives it, returned from it, or captured by a closure
that escapes it.

### Classified data

Capabilities constrain *effects*. Confidential content follows a second, independent set
of rules: `readClassified(path)` returns a `Classified[String]`, whose `map` and `flatMap`
take a function that may capture **read-only** capabilities only (`T ->{any.rd} B`). Every
outward channel needs a *full* one (`println`/`ask`/`chat` need `UserIO^`, `write` needs
`FileSystem^`, `exec` needs `Exec`, `httpGet` needs `Network`), so none of them can appear
inside a `map`. The agent can compute on a secret but never see it; `toString` is
`Classified(***)`. There are only a few deliberate output paths: `println` (you see the
value in the terminal, marked `[classified]`; the model sees `Classified(***)`),
`writeClassified` into a classified path, `chat(Classified)` with the configured classified
model, and
`httpPostClassified` / `secretHeaders` to an allow-listed host.

Here it is at work. `secrets/` is classified in the project config; the agent is asked a
question about a key it must never see:

```scala
> secrets/api.env holds our vendor key. Is it a live key? They start with "sk-live".

● run_scala
  │ val key = readClassified("secrets/api.env")
  │ val live = key.map(_.trim.stripPrefix("API_KEY=").startsWith("sk-live"))
  ├ result
  │ val key: Classified[String]^{} = Classified(***)
  │ val live: Classified[Boolean]^{} = Classified(***)
  └ ok 41 ms

● The check ran, but a value computed from a secret is a secret too, so I cannot read
  the answer either. I will print it for you.

● run_scala
  │ println(live)
  ├ output
  │ [classified] true
  └ ok 9 ms

● Your terminal shows the answer; on my side that line reads `Classified(***)`.
```

The type system enforces the boundary. Every way to move the key *out* of the `map` requires
a full capability, but the function passed to `map` may capture only read-only capabilities.
The compiler therefore rejects every attempt, whether the destination is the terminal, a
file, a process, or the network:

```scala
● run_scala
  │ key.map(k => println(k))                                   // or: write("leak.txt", k)
  ├ error                                                      //     exec("curl", List(url + k))
  │ Reference `rs$line$3` is not included in the allowed capture set {any.rd}
  │ of an enclosing function literal with expected type String -> Unit.
  │
  │ Note that `rs$line$3` is a capability because it contains a field user: atc.lib.UserIO^.
  └ failed 88 ms
```

(`rs$line$3` is the preamble line that holds the `user` given; the `exec` and `write`
attempts name the lines that hold `ex` and `fs`.) The read-only/full distinction matters
here: **the same line compiles in one mode but not another** because the view of `fs`
changes. In full mode, `fs` is the full view, so even a harmless read
inside the `map` captures a full capability and is refused:

```scala
> key.map(k => k + read("notes.md"))               // full mode: fs is the full view
Reference `rs$line$4` is not included in the allowed capture set {any.rd} …
```

In read-only mode, `fs` is `FileSystem^{io.rd}`, so the identical line is accepted
(`val res0: Classified[String]^{} = Classified(***)`). Reading cannot leak the secret,
whereas writing could; the capability view distinguishes the two. The agent can always
route a secret to an explicitly authorized channel: the terminal, a classified file, the
classified model, or an allow-listed host.

### What the types do not know

Types decide what compiles, but they know nothing about your configuration. Every host method
therefore also checks the permission policy for the relevant path, command, or host, and a
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

In read-only mode, a write is an `update` call through a read-only view (the error shown
[above](#what-it-looks-like)); in local mode there is no full `IOCap` to derive a `Network`
from, so a network call simply has no given to resolve. Either way a mode can withdraw the
agent's access to the machine while leaving the conversation intact. The agent can therefore
always explain what it *would* have done; the system prompt directs it to do so instead of
trying to bypass the mode. The policy enforces the same three levels again at run time, so
the type check is not the only safeguard.

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

You receive a pop-up (*Yes, this time* / *Yes, for the rest of this session* / *No*). If
approved, the block runs with the extra permission. The result tells the agent what you
decided, so "this time" requires another request next time, while "for the session" does
not. `locked` rules cannot be widened at all, and a
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

**`~/.atc/config.json` is the base; there is no implicit policy behind it.** No policy is
compiled into the program: anything not granted by a configuration is denied. The
[starting config](app/resources/atc/config-template.json) written on the first run protects
without granting access: it lists the providers, classifies common credential paths, puts
`.atc` itself out of reach, refuses `rm -rf *` and `sudo *`, and grants no files, commands, or
host. Edit it to grant things machine-wide.

**A directory is workable because a config says so.** The
[project config](app/resources/atc/project-template.json) that `atc --init` writes (or the
first run in a directory offers) opens the project: its own tree (with `./.git` read-only
and `./secrets` classified), the read-only git commands, and a set of documentation hosts.
ATC finds the project layer by walking up from the working directory, much as Git finds
`.git`, and resolves its relative patterns against the directory containing `.atc`.

A project's configuration resides inside the repository, so it may open *that repository*
but nothing beyond it, and it cannot exceed limits set by the machine's owner. Its file
rules grant access only within the project directory and can only narrow access elsewhere.
The `commands` and `hosts` lists are combined across all layers; deny lists provide the
backstop when a project reaches beyond its tree. `denyCommands` and `denyHosts` accumulate,
and no layer can remove an entry. Scalar limits (`mode`, `safeMode`, `maxToolCalls`,
`executionTimeoutMs`, …) can only become stricter. Non-permission settings (`model`,
`providers`, `instructions`, …) merge in layer order, with later values taking precedence.
Permissions that *you* grant in a pop-up are not narrowed by a layer because the human is
the final authority. The exact rules are in
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
  "executionTimeoutMs": 300000,
  "maxToolCalls": 200,
  "predictInput": true,
  "instructions": "Use 2-space indentation."
}
```

### Providers and models

A **provider** defines one endpoint (`api`, an optional `url`, and a key) and its `models`.
A **model** is an alias with a provider-specific `name` (which defaults to the alias) and
its own settings (`contextWindow`, `reasoning`, `webSearch`, `thinking`, `maxTokens`, …).
The supported `api` values are `anthropic` (Messages API), `openai-responses` (Responses
API, including DeepSeek and other services through `url`), `openai` (Chat Completions for
OpenAI-compatible servers such as Ollama, vLLM, or OpenRouter), and `echo` (a keyless
provider for smoke tests). Name a model
by its alias (`"model": "claude"`, `/model sonnet`), or by `provider/alias` when two
providers share one; `/models` lists them. Set `contextWindow` to the model's real window:
when the conversation no longer fits, the oldest exchanges are dropped and you are warned.

**Keys** are never stored directly in a configuration. A provider names a variable
(`"key": "${DEEPSEEK_API_KEY}"`) whose value comes from `.atc/keys.properties` (first the
project file, then `~/.atc/keys.properties`, and finally the environment). The starter
policy hides `.atc` from the agent, and `/config` shows only
which variables are bound, never values.

**Two roles**: `model` is the agent and never sees classified data; `classifiedModel`
handles `Classified` values (`chat(Classified)`), so point it at something you trust with
your secrets, or leave it unset. `/model` and `/classifiedmodel` switch them for the
session. Per-adapter settings are listed in
[doc/development.md](doc/development.md#models-and-providers).

### File permissions, commands and hosts

Each file rule has a `path` pattern and may specify `access` (`none|read|write`),
`classified`, and `locked`. Patterns follow gitignore-style conventions. A pattern without
`/` matches a path **component** anywhere (`.env`, `*.pem`, `node_modules`). A relative
pattern containing `/` is resolved against the working directory—or the project directory
in a project configuration—and may contain `*`, `**`, `?`, or `[…]`. Absolute paths and
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
but not `git statusx`). A command also needs read access to the directory it runs in. A pre-approved
command runs with your privileges and is *not* subject to the file rules, so pre-approve the
subcommands you mean rather than `git *`. `hosts` are glob patterns on host names; only
`http`/`https` URLs are accepted and redirects are not followed. `denyCommands` and
`denyHosts` use the same syntax: **a deny rule overrides every allow rule**, including a
session grant, an open `request*` scope, and `--approve-all`.

## What it protects, and what it does not

Security guarantees need clear boundaries. This section states what ATC guarantees, the
assumptions behind those guarantees, and how to use it safely.

### What holds

- **No ambient authority.** The Scala the model writes can do only what the capabilities in
  scope allow. Calling a method without the required capability is a compile error, so the
  restriction is a typing rule applied before the code runs.
- **Deny by default.** No policy is compiled into the program. A file, command, or host is
  reachable only when a configuration you control grants access.
- **Capabilities cannot escape.** The wider permission a `request*` block lends cannot be
  stored, returned, or captured to outlive the block, and the host closes the scope on exit.
- **Secrets stay typed.** `Classified` content can be computed on but not routed to a
  channel you did not authorize, and a computation that fails on a secret is not turned into
  a one-bit oracle.
- **Deny wins.** `denyCommands`/`denyHosts` override every allow, every session grant, every
  open scope, and `--approve-all`.

### What it assumes

- **You are trusted; the model is not.** ATC defends against a mistaken, confused, or
  prompt-injected *model* exceeding the access you granted. It does not defend the machine
  against *you*: a permissive configuration, `--approve-all`, or a permission granted in a
  pop-up is applied as specified.
- **An allowed command is arbitrary code, run with your privileges, outside the sandbox.**
  The capability system governs the Scala the model writes, not what a program you
  permitted then does. A permitted `bash`, `sh`, `python`, `node`, `make`, or a `git` that
  runs hooks can do anything you can, unconstrained by capabilities, classified data, or the
  mode. **Pre-approve narrow, specific subcommands (`git status`, `./mill test`); never grant
  an interpreter, a shell, or a wildcard like `git *` over a tool that can run code.**
- **Allowed hosts can receive data.** The agent may send any non-classified data available
  to it to an allowed host. The type system prevents this only for `classified` content.
  Allow only hosts you trust to receive project data.
- **Your provider sees your context.** Everything that is not `classified` (your prompts,
  the file contents the agent reads, command output) goes to the model provider you
  configured. Choose providers you trust; route secrets to a `classifiedModel` you trust (a
  local model, say) or leave it unset so they never leave the machine.
- **The trusted computing base.** The JVM, the Scala compiler and its capture checker, the
  OS, the terminal library, the agent library's host implementation, and ATC itself are
  trusted; a bug in any of them (or in your config) can break a guarantee. Capture
  checking and safe mode are experimental compiler features.

### Keeping it safe in practice

- Grant the least you need, and start in the least mode that works: read-only, then local,
  then full (which is the one that adds the network).
- Do not grant shells or interpreters as commands; list the exact subcommands you mean.
- Keep the configuration concise and auditable. Prefer several specific rules to one broad
  rule.
- Keep credentials behind `classified`, keep `.atc` out of the agent's reach (the templates
  do both), and keep `safeMode` on.
- Use `denyCommands` and `denyHosts` as a firm backstop for prohibited operations because a
  deny rule overrides every other permission.
- For risky operations, prefer a one-time grant in the pop-up to a broad standing grant.
  Reserve `--approve-all` for trusted sandboxes and CI environments.

## The terminal

`/help` lists the slash commands: `/model`, `/classifiedmodel`, `/models`, `/mode`, `/perms`,
`/config`, `/todos`, `/cost` (tokens, and how full the context is), `/interface` (the API the
model sees), `/run <code>` (run Scala in the sandbox yourself, with the agent's API and
permissions; the agent is told what you ran), `/ps` and `/kill [id|all]` (the processes the agent
started with `spawn`), `/reset` (fresh REPL, kills them too), `/clear` (forget the
conversation), `/new` (both, plus the session's grants), `/quit`. Ctrl-C interrupts the
turn, Ctrl-O shows folded output and reasoning in full, Shift-Tab cycles the mode, Ctrl-D
quits, Tab completes commands. Shift+Enter (or `\` then Enter) adds a line to the input; a
`/run` also continues while a bracket is open; Enter on an empty line submits.

After each turn, the model predicts your next request and displays it as ghost text (Tab or
→ accepts it; `"predictInput": false` disables it). A summary line shows the turn's cost
and how much of the context window is in use. Output is kept short: long program output
folds into a live tail, long results are cut in the middle, reasoning collapses to one line. Without a
terminal (`-p` in a pipe) everything is printed plainly; `ATC_ASCII=1` draws with ASCII
glyphs. The content shapes, keys and multi-line rules are in
[doc/development.md](doc/development.md#the-terminal).

## License

Apache-2.0.
