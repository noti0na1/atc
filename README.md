# ATC: A Minimal Agent With Tracked Capabilities

[![Scala CI](https://github.com/noti0na1/atc/actions/workflows/scala.yml/badge.svg)](https://github.com/noti0na1/atc/actions/workflows/scala.yml)

ATC (Agent with Tracked Capabilities) is a terminal coding agent that uses a Scala 3 REPL
and a capability-typed API for file access, commands, HTTP requests and user interaction.
Each snippet is compiled before execution. Capture checking restricts the capabilities it
can retain, safe mode restricts available APIs, and the host checks configured permissions
at runtime.

The design is derived from [TACIT](https://github.com/lampepfl/tacit). ATC adds a terminal
interface, persistent REPL sessions, multiple model providers and layered permissions.

- **Capability checks:** file writes, commands and network requests require the appropriate
  capabilities in scope.
- **Classified data:** confidential values remain wrapped in `Classified` and can be sent
  only through supported output methods.
- **Permission rules:** access is denied by default; configuration, temporary grants and
  session grants determine which operations are permitted. Deny rules take precedence.
- **OS sandbox:** on macOS and Linux, the commands the agent runs and the process that runs
  its code are confined to what the permission rules allow.
- **Undo:** the files a turn changes are recorded, and `/undo` reverts them.

These are different levels of protection, each resting on different parts of the system;
the compiler, the host and the OS are all part of the trusted implementation. See
[Security model](#security-model) for what each level guarantees and for the limits.

## Example

Here is a request, the Scala code the agent wrote, the program output, and the final answer:

```scala
> which methods in the library can mutate a file?

● run_scala
  │ grepRecursive("lib/src", "^\\s+update def", "*.scala")
  │   .foreach(m => println(s"${m.lineNumber}  ${m.line.trim}"))
  ├ output
  │ 114  update def write(content: String): Unit
  │ 115  update def writeBytes(content: Array[Byte]): Unit
  │ 116  update def append(content: String): Unit
  │ 117  update def delete(): Unit
  │ 119  update def mkdir(): Unit
  │ 129  update def writeClassified(content: Classified[String]): Unit
  └ ok 121 ms

● Six of them: write, writeBytes, append, delete, mkdir and writeClassified. They are
  declared `update`, so they can only be called through a full `FileSystem^`.
```

`grepRecursive` and `println` are not strings ATC parses: they are methods of
`atc.lib.Interface`, each demanding a capability value that only the sandbox hands out.
The snippet was compiled before it ran. The REPL keeps its state between snippets, so a
`val` defined in one turn is still there in the next.

In **read-only mode** the same agent cannot express the write. The sandbox provides a
read-only file system, and `write` is an `update` method that requires a full view. The
compiler rejects the call, and the agent explains why:

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

When the agent needs access the configuration does not grant, it requests exactly that
access within a block the extra permission cannot escape. You decide in a pop-up:

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
    › Allow once
      Allow for this session
      Deny this request
      Tell the agent what to change
```

## Setup

You need JDK 17 or newer.

**macOS and Linux.** The `atc` wrapper script downloads the jars of the latest
[GitHub release](https://github.com/noti0na1/atc/releases), checks them against the
digests GitHub records, and runs them:

```bash
curl -fsSL https://raw.githubusercontent.com/noti0na1/atc/refs/heads/main/atc -o atc
chmod +x atc
./atc setup      # installs ~/.local/bin/atc, puts it on PATH, downloads the latest release
```

From then on `atc` runs ATC in the current directory, and offers to upgrade when a newer
release is out. `atc help` lists the wrapper's commands (update, uninstall, …). To run from
a checkout instead, see
[doc/development.md](doc/development.md#building-and-running).

<details>
<summary><strong>Windows (best-effort support)</strong></summary>

Windows support is best effort. In PowerShell, with JDK 17+ installed, download the
`atc.ps1` wrapper and run its setup, which downloads the latest release and checks it the
same way:

```powershell
irm https://raw.githubusercontent.com/noti0na1/atc/refs/heads/main/atc.ps1 -OutFile atc.ps1
powershell -ExecutionPolicy Bypass -File .\atc.ps1 setup   # installs atc in %USERPROFILE%\.atc\bin and puts it on PATH
```

In a new terminal, `atc` then runs ATC in the current directory and offers to upgrade when a
newer release is out; `atc help` lists the wrapper's commands. If PowerShell refuses to run
scripts, use `atc.cmd`, or allow local scripts with
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

</details>

**1. Start it.** Change to the project you want to work on (`cd ~/my-project`) and run
`atc`. The first start asks you to choose a provider, paste its API key and choose one of
its models; the key is saved in `~/.atc/keys.properties`, readable only by you. With a
ChatGPT plan you can sign in through the browser instead of giving a key, and with a
Claude plan ATC can use your signed-in [Claude Code](https://claude.com/claude-code) CLI. To set up
providers by hand instead, choose *Configure providers myself*: ATC writes
`~/.atc/config.json` and exits. On Windows, `~/.atc` is `%USERPROFILE%\.atc`.

**2. Open the project.** If no configuration grants access to the current directory and
the directory has no `.atc/config.json` of its own, ATC offers to create a starter one there
and applies it immediately. That file is what opens the project to the agent: its own tree,
the read-only git commands and a set of documentation hosts. Review it to choose which
**files, commands and hosts** the agent may use without asking; see
[Configuration](#configuration). `/models` lists the models your providers offer, and
`/model` picks one.

**3. Talk to it.** Type a request at the prompt; the agent answers by writing and running
Scala in the sandbox, and asks before touching anything the config does not grant. `/help`
lists the slash commands, Ctrl-C interrupts a turn, Ctrl-D quits. The most useful flags are
`-m <alias>` to pick a model, `--mode readonly|local|full` to pick a sandbox mode, and
`-p "<request>"` to run one turn from the shell and exit:

```bash
atc -p 'summarise the README'
```

## Capabilities and ambient authority

In ordinary Scala, any code can call `Files.readString` or start a process: authority is
*ambient*, available to anyone who can name the method. The agent-facing library takes that
away. Every effectful method demands the capability it needs as a `using` parameter:

```scala
def read(path: String)(using FileSystem): String
def write(path: String, content: String)(using FileSystem^): Unit
def exec(command: String, args: Seq[String])(using Exec^, FileSystem^): ProcessResult
def httpGet(url: String)(using Network^): String
def println(x: Any)(using UserIO^): Unit
```

A snippet can therefore perform only the operations allowed by the givens in its scope. It
cannot create new capabilities: agent code cannot construct the capability classes, the
REPL preamble binds the only instances, and safe mode prevents access to the object that
holds the roots.

### Two roots

The preamble binds one root for effects on the machine and one for talking to the human.
The sandbox derives the machine capabilities from the first, and each mode publishes only
the capabilities it permits:

```
                        ┌── fs:  FileSystem^{io}    read files; write with a full view
Runtime.rootIO  ──► io ─┼── ex:  Exec^{io}          run permitted commands
                        └── net: Network^{io}       reach permitted hosts

Runtime.rootUser ──► user: UserIO^                  println · print · ask · setTodos · chat
```

`io` is the common capture root for every published machine capability. Local and full mode
expose it as `IOCap^`. The derivations are sandbox-internal, so
holding the root does not create a capability omitted by the current mode.
`exec` and `spawn` require both full capabilities, `Exec^` and `FileSystem^`; `execReadOnly`
needs only a read-only `FileSystem`, and the OS sandbox keeps such a command from writing.
A command gets the network only through `withNetwork`, which needs `Network^`, so a
snippet's capture set shows whether its commands can reach a host.

| Capability | What it authorises | Where one comes from |
|---|---|---|
| `IOCap` | nothing by itself; it is the root the others are derived from | the preamble (`given io`) |
| `FileSystem` | `read`, `ls`, `walk`, `grep`, …; `write`, `append`, `delete`, `mkdir` need a full one | the preamble's `fs` (derived from `io` by the sandbox; `val ro: FileSystem^{fs.rd} = fs` is a read-only view) |
| `FileEntry` | a handle to one file or directory; as capable as the `FileSystem` it came from | `access(path)` |
| `Exec` | running commands: `exec`/`spawn` with a full `FileSystem^`, `execReadOnly` with a read-only one; `withNetwork` adds the network | the preamble's `ex` (every mode; read-only mode's runs only `execReadOnly`) |
| `Network` | HTTP requests | the preamble's `net` (full mode) |
| `UserIO` | printing, questions, the TODO list, and normal-model `chat` | the preamble (`given user`), always full |

`UserIO` is not derived from `IOCap`: reporting to the human is an effect on the
conversation, not on the machine, so it survives when the agent may touch nothing at all.

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
untrusted outward channel needs a *full* one (`println`/`ask`/normal-model `chat` need
`UserIO^`, `write` needs `FileSystem^`, `exec` needs both `Exec^` and `FileSystem^`, and
`httpGet` needs `Network^`), so none of them can appear inside a `map`. The agent can compute
on a secret but never see it; `toString` is
`Classified(***)`. The output paths are `println` (you see the
value in the terminal, marked `[classified]`; the model sees `Classified(***)`),
`writeClassified` into a classified path, `classifiedChat` with the configured classified
model, and `httpPostClassified` / `secretHeaders` to an allow-listed host. A response to a
request carrying classified content stays `Classified`, so a peer cannot reflect a secret
header or body back into a plain model-visible value.

In this example `secrets/` is classified in the project config, and the agent is asked a
question about a key it must never see:

```scala
> secrets/api.env holds our vendor key. Is it a live key? They start with "sk-live".

● run_scala
  │ val key = readClassified("secrets/api.env")
  │ val live = key.map(_.trim.stripPrefix("API_KEY=").startsWith("sk-live"))
  ├ result
  │ val key: Classified[String] = Classified(***)
  │ val live: Classified[Boolean] = Classified(***)
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

`rs$line$3` is the preamble line that holds the `user` given; the `exec` and `write`
attempts name the lines that hold `ex` and `fs`. The same line can compile in one mode and
not another, because the view of `fs` changes. In local and full mode `fs` is the full
view, so even a harmless read inside the `map` captures a full capability and is refused:

```scala
> key.map(k => k + read("notes.md"))               // local/full: fs is the full view
Reference `rs$line$4` is not included in the allowed capture set {any.rd} …
```

In read-only mode `fs` is `FileSystem^{io.rd}`, so the identical line is accepted. Reading
cannot leak the secret, whereas writing could, and the capability view distinguishes the
two. The agent can always route a secret to an authorized channel: the terminal, a
classified file, the classified model, or an allow-listed host.

### Runtime permissions

Types decide what compiles, but they know nothing about your configuration. Every host method
therefore also checks the permission policy for the relevant path, command, or host, and a
capability from a `request*` block is refused once that block has closed. Underneath sit a
validator that rejects the obvious escape hatches (`java.io`, reflection, the application's
own packages) before compilation, and a class loader that shows agent code only the JDK,
`scala.*` and the agent library. On macOS and Linux the OS sandbox adds a further level; see
[Levels of protection](#levels-of-protection). The details are in
[doc/development.md](doc/development.md#defence-in-depth).

## Modes: read-only, local, full

A **mode** decides which capabilities the preamble puts in scope, and therefore what the
agent can express at all, before the permission policy applies:

| Mode | The agent can |
|---|---|
| **read-only** | read files, report, ask, and run commands that write nothing |
| **local** | also write files and run commands that write |
| **full** | also reach the network, and let the model's provider search the web |

A mode withdraws an effect while leaving the conversation intact, so the agent can always
explain what it *would* have done. The policy enforces the same three levels again at run
time. Switch with `/mode` (cycles the three), **Shift-Tab** on an empty prompt, `--mode`,
or `"mode"` in the config; switching starts a fresh REPL but keeps the conversation. The
default is full.

## Asking for more

Anything the configuration already permits works. When an operation is denied, the
exception names the block that can ask for it, and the agent wraps only that operation:

```scala
requestFiles(".cache/atc", Access.Write, reason = "cache build outputs") {
  write(".cache/atc/out.txt", "done")     // a wider FileSystem^ is the given inside the block
}
requestExec(Set("npm *"), "install deps") { exec("npm", List("install")) }
requestNetwork(Set("api.github.com"), "check PRs") { httpGet("https://api.github.com/...") }
```

The pop-up offers **Allow once**, **Allow for this session**, **Always allow in this
project**, which also saves the grant to the project's `.atc/config.json`, **Deny this
request**, and **Tell the agent what to change**. The last one sends your instructions back
instead of a grant: "request only the first four commands; skip the deployment" makes the agent revise
its request. The granted capability cannot leave the block, and the host closes the scope
when the block exits. `locked` rules cannot be widened at all, and a `denyCommands` or
`denyHosts` match is refused without a pop-up.

## Configuration

Config files are JSON, in three layers:

| | layer | file | may |
|---|---|---|---|
| 1 | global | `~/.atc/config.json` | grant anything |
| 2 | project | the nearest `.atc/config.json` at or above the working directory | open **its own project**; narrow anything |
| 3 | explicit | `-c <file>` | grant anything |

No policy is compiled into the program: anything not granted by a configuration is denied.
The [starting global config](app/resources/atc/config-template.json) protects without
granting: it lists the providers, classifies common credential paths, puts `.atc` out of
reach, refuses `rm -rf *`, `sudo` and bare shells, and grants no files, commands or hosts.
The [project config](app/resources/atc/project-template.json) opens a project, and because
it lives inside the repository it can open *that repository* but no files beyond it, cannot
loosen the limits the global config sets, and cannot change a provider's address or key.
What it adds beyond its own files (pre-approved commands and hosts, a classified model, the
keys in its `.atc/keys.properties`) applies only after ATC has shown it to you and you chose
to trust it; ATC asks again when that part changes.
`/config` changes the settings that leave the sandbox alone (input prediction,
notifications, web search, compaction) for the session or in either file. The exact merge
rules are in [doc/development.md](doc/development.md#configuration-semantics).

```json
{
  "model": "claude",
  "providers": {
    "anthropic": {
      "api": "anthropic",
      "models": {
        "claude": { "name": "claude-opus-5", "webSearch": true, "reasoning": "high", "contextWindow": "200k" }
      }
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
    { "path": "secrets",  "classified": true },
    { "path": "~/notes",  "access": "read", "locked": true }
  ],
  "commands": ["git status", "git log"],
  "denyCommands": ["git push*", "git *--output*", "rm -rf *", "sudo", "sh", "bash", "zsh", "powershell"],
  "hosts": ["*.scala-lang.org", "docs.oracle.com"],
  "mode": "full",
  "instructions": "Use 2-space indentation."
}
```

**Models.** A provider is one endpoint (`api`, an optional `url`, a key) with its
`models`; leave `models` out and ATC lists the provider's own. `/providers` turns providers
and their models on or off, or adds a provider. `/model` switches the model and `/effort`
its reasoning effort, both remembered in the project config; `webSearch` turns on the
provider's web search where it has one. **Keys** never go in a config: `"key": "${DEEPSEEK_API_KEY}"` names a variable set
in `.atc/keys.properties` or in the environment. Set `classifiedModel` only for a model that
runs in an isolated environment with no outward connection; without one, the default,
classified data goes to no model. `/classifiedmodel` chooses one, or `none`. Every setting is described in
[doc/development.md](doc/development.md#configuration-reference).

**Files, commands and hosts.** A file rule has a gitignore-style `path` pattern (a bare
name matches that component anywhere; a pattern with `/` is relative to the working
directory, or the project directory in a project config), an `access` of `none`, `read`
or `write`, and optionally `classified` and `locked`. A rule covers the matched path's
whole subtree, effective access is the minimum over matching rules, and no match means no
access. `commands` are patterns over the whole command line (`"git status"` also allows
`git status --short`; `*` is a wildcard); `hosts` are glob patterns on host names.
`denyCommands` and `denyHosts` use the same syntax and override every allow, session grant
and open scope. A pre-approved command runs with your privileges and outside the file
rules, so pre-approve the subcommands you mean rather than `git *`, and check their options:
`git diff` and `git blame` can print any file, and `git log --output` can write one. Pattern details and
Windows notes are in [doc/development.md](doc/development.md#file-rules-and-command-patterns).

## Security model

### Levels of protection

ATC protects your machine at several levels. They differ in what they can tell apart and in
the parts of the system they rest on:

| Level | What it guarantees | What it covers | Rests on |
|---|---|---|---|
| **Types** (capture checking, safe mode) | a snippet uses only the capabilities in scope; secrets stay inside `Classified`; a lent capability does not outlive its block | the Scala the agent writes | the compiler and the agent library |
| **Permission policy** | every file, command and host access matches your rules, grants and deny lists | every operation the agent's code performs | ATC's host implementation |
| **OS sandbox** (macOS, Linux) | commands write only where your rules allow, read only granted paths, system files and toolchains, never your credentials or classified files, and reach only allowed hosts; the agent's code runs in a separate process that holds no keys and reaches the machine only through ATC | commands and the programs they start, and the process running the agent's code | the OS sandbox (Seatbelt, bubblewrap) |
| **Undo** | the files a turn changed can be listed and reverted | changes made by the agent's code and by commands | git and ATC's store in `~/.atc` |

The type level is the most precise: it knows which snippet or closure holds which
capability and separates classified values from ordinary ones, which no OS mechanism can.
The OS level is coarser (it knows paths and hosts, not values), but it also covers code the
types never see, such as a build or test suite a permitted command runs. Where levels
overlap, each one limits what a fault in another can reach: the policy is checked on every
operation whatever the types allowed, and the OS sandbox enforces the same rules on
processes. Undo covers what no rule can prevent, a change that the rules allow but you did
not want.

### Enforced restrictions

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
- **Commands are confined.** On macOS and Linux every command runs in an OS sandbox derived
  from your file rules: it writes only where the agent may write, never into `.git` hooks or
  configuration, `.atc` or editor settings, cannot read classified files or your credentials,
  and has no network unless the agent starts it inside `withNetwork` (full mode), which lets
  it reach only the hosts you allow, through a proxy that asks you about others. A command
  run with `execReadOnly` writes nothing, which is how read-only mode runs commands.
- **Agent code runs apart.** On macOS and Linux the compiler and the agent's code run in a
  separate process that the OS sandbox keeps from your files, keys and network: it reaches
  the machine only through ATC's operations, which apply the permission policy.
- **Changes can be undone.** After a turn ATC lists the files it changed, whether the agent's
  code or a command changed them, and `/undo` restores them, keeping edits you made since
  where it can.

### Assumptions and limits

- **You are trusted; the model is not.** ATC defends against a mistaken, confused, or
  prompt-injected *model* exceeding the access you granted. It does not defend the machine
  against *you*: a permissive configuration, `--approve-all`, or a permission granted in a
  pop-up is applied as specified.
- **An allowed command is arbitrary code.** The OS sandbox bounds what it can touch, not what
  it computes: within the project it can change any file the agent may write, and inside
  `withNetwork` it can send data to any host you allow. **Pre-approve narrow, specific
  subcommands (`git status`, `./mill app.test`); avoid granting an interpreter, a shell, or a
  wildcard like `git *` over a tool that can run code.**
- **The OS sandbox is not everywhere.** On Windows, or where the sandbox is unavailable (for
  example Linux without bubblewrap or with unprivileged user namespaces disabled), commands
  run with your privileges and the agent's code runs inside ATC; the banner says so. Set
  `"osSandbox": "required"` to refuse commands and the REPL instead.
- **The OS sandbox works on paths.** A copy of a secret under an unclassified name is not
  recognised as classified, by commands or by ATC. Commands cannot reach local servers or
  Unix sockets, so build tools must run without their background server
  (`./mill --no-daemon`), and tool caches in your home directory are read-only to them.
- **Allowed hosts can receive data.** The agent may send any non-classified data available
  to it to an allowed host. The type system prevents this only for `classified` content.
  Allow only hosts you trust to receive project data.
- **Your provider sees your context.** Everything that is not `classified` (your prompts,
  the file contents the agent reads, command output) goes to the model provider you
  configured. Use `classifiedModel` only for a deployment that satisfies the isolated,
  effect-free assumption (a locked-down local model, for example), or leave it unset.
- **Classified flow is termination-insensitive.** Capture checking prevents effects in
  `Classified.map`, but arbitrary pure code can still vary its running time, resource use,
  or termination with the secret. Do not treat timeouts or timing as a declassification
  mechanism; ATC does not claim resistance to those side channels.
- **Undo has limits.** It works in interactive sessions and restores tracked and untracked
  files that git does not ignore. Ignored files, new files over 2 MiB and classified files
  are not recorded, and a command's effects outside the project (a network request, for
  example) cannot be undone.
- **The trusted computing base.** The JVM, the Scala compiler and its capture checker, the
  OS and its sandbox, the terminal library, the agent library's host implementation, and ATC
  itself are trusted; a bug in any of them (or in your config) can break a guarantee.
  Guarantees rest on different parts: `Classified` confidentiality and the per-snippet
  capability discipline rest on the compiler, including when the agent's code runs in its
  own process, while command confinement rests on the OS sandbox. Capture checking and safe
  mode are experimental compiler features.

### Permission configuration

- Grant the least you need, and start in the least mode that works: read-only, then local,
  then full (which is the one that adds the network).
- Do not grant shells or interpreters as commands; list the exact subcommands you mean.
- Keep the configuration concise and auditable. Prefer several specific rules to one broad
  rule.
- Keep credentials behind `classified`, keep `.atc` out of the agent's reach (the templates
  do both), and keep `safeMode`, `checkpoints` and the OS sandbox (`osSandbox`) on.
- Use `denyCommands` and `denyHosts` to prohibit operations regardless of other permissions.
- For risky operations, prefer a one-time grant in the pop-up to a broad standing grant.
  Reserve `--approve-all` for trusted sandboxes and CI environments.

## The terminal

`/help` lists the slash commands, and typing `/` lists them under the prompt (↑/↓ select,
Tab fills in, Enter runs). Ctrl-C interrupts the turn. Finished code runs fold
to a summary (`/output <n>` shows one in full), and Ctrl-O switches to writing code, output
and reasoning out in full, Shift-Tab cycles the mode, and Shift+Enter adds a line. While the
agent is working, type a correction and press Enter: it reaches the agent before its next
tool call. After a turn that changed files, ATC lists them, and `/undo` reverts them.
Sessions are saved when you leave, and the next start in the same directory
offers to resume. ATC notifies you when a turn ends or the agent waits for you.

Without a terminal (`-p` in a pipe) nothing asks: a permission the configuration does not
grant fails instead of waiting, so use `--approve-all` only in a trusted setup.

## License

Apache-2.0.
