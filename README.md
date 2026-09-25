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

The compiler and host are part of the trusted implementation. External commands run with
the user's OS privileges. See [Security model](#security-model) for the assumptions and limits.

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

Windows support is best effort and has no installer or automatic updater yet. From the
[latest release](https://github.com/noti0na1/atc/releases/latest), download `atc.ps1`,
`atc.cmd`, `atc.jar` and `atc-lib.jar` into one directory. With JDK 17+ on `PATH`, run ATC
from your project directory:

```powershell
Set-Location 'C:\path\to\your-project'
& 'C:\path\to\atc\atc.ps1'
```

Prefer the PowerShell launcher, which preserves Unicode and complex arguments; `atc.cmd` is
a compatibility entrypoint for Command Prompt. To update, replace all four files with the
assets of a newer release.

</details>

**1. Start it.** Change to the project you want to work on (`cd ~/my-project`) and run
`atc`. The first start asks you to choose a provider, paste its API key and choose one of
its models; the key is saved in `~/.atc/keys.properties`, readable only by you. With a
ChatGPT plan you can sign in through the browser instead of giving a key. To set up
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
Command operations require both full capabilities, `Exec^` and `FileSystem^`; every mode that
publishes `ex` also publishes a full `fs` under the same root.

| Capability | What it authorises | Where one comes from |
|---|---|---|
| `IOCap` | nothing by itself; it is the root the others are derived from | the preamble (`given io`) |
| `FileSystem` | `read`, `ls`, `walk`, `grep`, …; `write`, `append`, `delete`, `mkdir` need a full one | the preamble's `fs` (derived from `io` by the sandbox; `val ro: FileSystem^{fs.rd} = fs` is a read-only view) |
| `FileEntry` | a handle to one file or directory; as capable as the `FileSystem` it came from | `access(path)` |
| `Exec` | running commands, together with a full `FileSystem^` | the preamble's `ex` (local and full mode) |
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
`scala.*` and the agent library. The details are in
[doc/development.md](doc/development.md#defence-in-depth).

## Modes: read-only, local, full

A **mode** decides which capabilities the preamble puts in scope, and therefore what the
agent can express at all, before the permission policy applies:

| Mode | The agent can |
|---|---|
| **read-only** | read files, report, ask |
| **local** | also write files and run commands |
| **full** | also reach the network |

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

The pop-up offers **Allow once**, **Allow for this session**, **Deny this request**, and
**Tell the agent what to change**. The last one sends your instructions back instead of a
grant: "request only the first four commands; skip the deployment" makes the agent revise
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
it lives inside the repository it can open *that repository* but nothing beyond it, and
cannot exceed the limits the global config sets. Review a project's `.atc/config.json`
before running ATC on code you do not trust: it chooses the models, commands and hosts.
The exact merge rules are in
[doc/development.md](doc/development.md#configuration-semantics).

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
  "commands": ["git status", "git diff*", "git log*"],
  "denyCommands": ["git push*", "rm -rf *", "sudo", "sh", "bash", "zsh", "powershell"],
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
runs in an isolated environment with no outward connection. Every setting is described in
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
rules, so pre-approve the subcommands you mean rather than `git *`. Pattern details and
Windows notes are in [doc/development.md](doc/development.md#file-rules-and-command-patterns).

## Security model

The following restrictions depend on safe mode, the compiler, the host implementation
and the configured permissions.

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

### Assumptions and limits

- **You are trusted; the model is not.** ATC defends against a mistaken, confused, or
  prompt-injected *model* exceeding the access you granted. It does not defend the machine
  against *you*: a permissive configuration, `--approve-all`, or a permission granted in a
  pop-up is applied as specified.
- **An allowed command is arbitrary code, run with your privileges, outside the sandbox.**
  The capability system governs the Scala the model writes, not what a program you
  permitted then does. A permitted `bash`, `sh`, `python`, `node`, `make`, or a `git` that
  runs hooks can do anything you can, unconstrained by capabilities, classified data, or the
  mode. **Pre-approve narrow, specific subcommands (`git status`, `./mill app.test`); never
  grant an interpreter, a shell, or a wildcard like `git *` over a tool that can run code.**
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
- **The trusted computing base.** The JVM, the Scala compiler and its capture checker, the
  OS, the terminal library, the agent library's host implementation, and ATC itself are
  trusted; a bug in any of them (or in your config) can break a guarantee. Capture
  checking and safe mode are experimental compiler features.

### Permission configuration

- Grant the least you need, and start in the least mode that works: read-only, then local,
  then full (which is the one that adds the network).
- Do not grant shells or interpreters as commands; list the exact subcommands you mean.
- Keep the configuration concise and auditable. Prefer several specific rules to one broad
  rule.
- Keep credentials behind `classified`, keep `.atc` out of the agent's reach (the templates
  do both), and keep `safeMode` on.
- Use `denyCommands` and `denyHosts` to prohibit operations regardless of other permissions.
- For risky operations, prefer a one-time grant in the pop-up to a broad standing grant.
  Reserve `--approve-all` for trusted sandboxes and CI environments.

## The terminal

`/help` lists the slash commands. Ctrl-C interrupts the turn, Ctrl-O shows folded output
and reasoning in full, Shift-Tab cycles the mode, and Shift+Enter adds a line. While the
agent is working, type a correction and press Enter: it reaches the agent before its next
tool call. Sessions are saved when you leave, and the next start in the same directory
offers to resume. ATC notifies you when a turn ends or the agent waits for you.

Without a terminal (`-p` in a pipe) nothing asks: a permission the configuration does not
grant fails instead of waiting, so use `--approve-all` only in a trusted setup.

## License

Apache-2.0.
