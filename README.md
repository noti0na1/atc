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

## What a turn looks like

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
The snippet was compiled before it ran; had it tried to write a file in read-only mode,
or reach the network without a `Network`, nothing would have happened. The REPL keeps its
state between snippets, so a `val` defined in one turn is still there in the next.

## Setup

You need JDK 17 or newer, and one of two ways to get `atc`:

**Install a release.** The `atc` wrapper script downloads the jars of the latest
[GitHub release](https://github.com/noti0na1/atc/releases), checks them against the
digests GitHub records, and runs them:

```bash
curl -fsSL https://raw.githubusercontent.com/noti0na1/atc/refs/heads/main/atc -o atc
chmod +x atc
./atc setup      # installs ~/.local/bin/atc, puts it on PATH, downloads the latest release
```

From then on `atc` runs ATC in the current directory, `atc update` fetches a newer release,
`atc self update` refreshes the wrapper, `atc self uninstall` removes both, and `atc help`
lists the wrapper's commands. The jars live in `~/.atc/jars/`, beside the global config
(`ATC_CACHE_DIR` to move them; `GITHUB_TOKEN` raises the API rate limit; `ATC_JAVA_OPTS`
adds JVM flags).

**Run from a checkout.** The repository ships its own Mill launcher (`./mill`), so there is
nothing else to install: `./start.sh` builds the distribution when sources changed and
runs it (see [Working on ATC itself](#working-on-atc-itself)). The examples below use
`atc`; with a checkout, `./start.sh` takes the same flags.

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

Alternatively export the variables in your shell (`start.sh` also sources a `.env` in the
repository, `cp .env.example .env`, without overriding what is already exported), or use a
local model that needs no key. Answer no and nothing is written: the built-in starting
config is used for that run (`atc --init-global` writes it later, on demand).

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

### Working on ATC itself

```bash
./mill app.test                  # ~200 munit tests, about 20 s
./mill -i app.run -C ~/project   # run without building a dist (-i keeps the terminal attached)
./mill __.reformat               # scalafmt; CI runs ./mill __.checkFormat
./mill dist                      # self-contained out/dist.dest/{atc,atc.jar,atc-lib.jar}
```

`start.sh` rebuilds `out/dist.dest/` with `./mill dist` when sources changed, sources a
`.env` (`cp .env.example .env`), and passes its flags through to `atc`; without the
script: `./mill dist`, then `out/dist.dest/atc`. `ATC_DEBUG=1` prints stack traces and
terminal diagnostics, `ATC_ASCII=1` draws the UI with ASCII glyphs only, `ATC_SKIP_BUILD=1`
makes `start.sh` skip its rebuild check.

**Releases.** Publishing a GitHub release whose tag is `v<Versions.atc>` (`build.mill`)
makes CI build the distribution and attach `atc.jar` and `atc-lib.jar` to the release;
that is what the `atc` wrapper downloads (`tests/atc_test.sh` covers the wrapper, and runs
in CI too).

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
object holding the roots (`atc.lib.Runtime`) is `@rejectSafe`: under safe mode, agent code
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
| `IOCap` | nothing by itself; it is the root the others are derived from | the preamble (`given io`) |
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

* the **bare** type (`FileSystem`, `IOCap`) is the **read-only** view;
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
channel needs a *full* one (`println`/`ask`/`chat` need `UserIO^`, `write` needs
`FileSystem^`, `exec` needs `Exec`, `httpGet` needs `Network`), so none of them can appear
inside a `map`. The agent can compute on a secret (and even read files, where its `fs` is
itself read-only) but never see it; `toString` is `Classified(***)`.

The ways out are deliberate and few: `println` (the human sees the value in the terminal,
marked `[classified]`; the model sees `Classified(***)`), `writeClassified` into a
classified path, `chat(Classified)` with the configured classified model, and `httpPostClassified`
/ `secretHeaders` to an allow-listed host.

### What the types do not know

Types decide what compiles; they know nothing about your configuration. So every host method
*also* checks the permission [`Policy`](#file-permissions) for the path, command or host in
question, and every capability value carries the id of the permission scope it was issued
for: a capability from a `request*` block is refused once that block has closed. Two more
layers sit underneath: a regex `CodeValidator` rejects the obvious escape hatches
(`java.io`, reflection, `caps.unsafe`, `atc.host`, catching fatal throwables) before
compilation, and the REPL's class loader exposes only the JDK, `scala.*` and `atc.lib.*`, so
the application itself (host, policy, LLM clients, the compiler) is invisible to agent
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

In **local** mode `fs` and `ex` are full, but `io` is only a read-only view, and `Network`
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
block runs with the extra permission. `locked` rules cannot be widened at all. The granted
capability cannot leave the block (capture checking), and the host closes the permission
scope when the block exits, so its scope id can never be used again.

`requestFiles` works in every mode: the file system it lends the block is exactly as capable
as the one you already hold (full in local and full mode, read-only in read-only mode), so
the same call site compiles everywhere.

## Configuration

### Layers

Config files are JSON, and there are three layers:

| | layer | file | may |
|---|---|---|---|
| 1 | global | `~/.atc/config.json` | grant anything |
| 2 | project | the nearest `.atc/config.json` at or above the working directory | open **its own project** (files inside its folder, commands, hosts); narrow anything |
| 3 | explicit | `-c <file>` | grant anything |

**`~/.atc/config.json` is the base, and there is nothing behind it.** No policy is compiled
into the program: what no config grants is not permitted. Nothing is written without asking
either: when the file is missing, an interactive run offers to write
[the starting config](app/resources/atc/config-template.json) (and the keys file beside it)
and then stops so you can fill in the keys; decline, or run with `-p`, and the same
starting config is used in memory for that run, as a layer `/config` shows as `(bundled)`.
`--init-global` writes it on demand. Once written it is never touched again. The starting
config protects without granting: it lists the providers, classifies the usual credential
paths, puts `.atc` itself out of reach, refuses `rm -rf *` and `sudo *`, and grants no
file, no command and no host. Edit it to grant things machine-wide.

**A directory is workable because a config says so.** The working directory is not special:
`atc -C /tmp/scratch` with no config covering it can read and write nothing. An interactive
run says so at startup and offers to write a
[project config](app/resources/atc/project-template.json) there (`atc --init` writes it
without asking); decline and the agent asks for each file. That config opens the project:

* it grants the project's own tree, with `./.git` read-only so the agent can read history
  but not rewrite it by hand (git *commands* are unaffected; they are governed by
  `commands`, not by file rules), and classifies `./secrets`;
* it pre-approves the read-only git commands (`git status`, `git log`, `git diff`, …) and
  refuses `git push*` and `git reset --hard*`;
* it opens the network to language documentation and the usual paper hosts (MDN,
  `docs.python.org`, `arxiv.org`, …): official docs and publishers, not package registries
  or code-hosting sites, since a permitted host is also somewhere `httpPost` can send data.
  Redirects are not followed, so a host the agent is sent on to has to be listed itself.

Everything else is left to the global config. Alternatively, grant the path from
`~/.atc/config.json` and skip the project config altogether.

The project layer is found by **walking up** from the working directory, the way git finds
`.git`: running atc in `repo/src/main` picks up `repo/.atc/config.json`. That config governs
the folder its `.atc` sits in, and its relative patterns are read against that folder, so
`"./build"` in `repo/.atc/config.json` always means `repo/build`, wherever atc was started.
Should the search reach your home directory, `~/.atc/config.json` stays the granting layer
rather than becoming a project one.

Note the asymmetry: a relative pattern in the *global* config still means "relative to the
working directory", since that config is not tied to any project. So `{ "path": ".",
"access": "write" }` in `~/.atc/config.json` opens whatever directory atc is started in
(and only that: started in `repo/src/main` it leaves the rest of `repo` alone), while the
same rule in a project config always opens the whole project. Start atc at the project root
if you want the agent to reach all of it.

**Settings that are not permissions** (`model`, `classifiedModel`, `providers`,
`instructions`, `predictInput`) merge in layer order, the later layer winning. Providers merge per provider
and then per model alias, so a project config can add a model to a provider the global
config defined without repeating its `api`, `url` or `key`.

**Permissions do not merge that way.** A project's `.atc/config.json` ships inside the
repository you are working in. It may open *that repository* (you chose to work there) but
nothing beyond it, and never past a limit the machine's owner set. The global and `-c`
layers grant anywhere; the project layer grants only what belongs to its project, and
otherwise only takes away:

* **`files`**: a path's access is what some rule *grants* it, clamped by the **minimum**
  over every rule matching the path *or an ancestor of it*. A rule grants wherever it
  matches, except a project rule outside its own project, which only clamps. So: nothing
  is reachable until a rule grants it; a rule on a directory covers its whole subtree and a
  deeper rule can only narrow that; a project config can open its own tree
  (`{ "path": ".", "access": "write" }`) but `{ "path": "~/.ssh", "access": "read" }` in one
  grants nothing, and neither does raising a path the global config restricted.
  `classified` and `locked` only ever restrict, so they apply from any layer and no layer
  can take them off again.
* **`commands` / `hosts`**: the union of every layer's list. A project config may
  pre-approve the commands and hosts its work needs, the way it opens its own files; there
  is no "inside the project" for a command or a host, so this is the one place a project
  config reaches beyond its tree. The deny lists are the backstop, and a repository you do
  not trust deserves a look at its `.atc/config.json` before you run atc in it.
* **`denyCommands` / `denyHosts`**: refusals, so every layer's patterns apply. Any layer can
  add one and none can drop one, so `"denyCommands": ["sudo *"]` in `~/.atc/config.json`
  holds against a project that lists `sudo apt` under `commands`.
* **`mode`, `safeMode`, `respectGitignore`, `maxToolCalls`, `maxToolOutputChars`,
  `executionTimeoutMs`**: the project layer moves each towards the stricter value (a lower
  mode or limit, a flag switched on) or leaves it alone. A setting it does not mention is
  not narrowed, which is why leaving a key out and setting it to its default are different
  things. `safeMode` in particular is a **latch**: it is on unless a granting layer says
  `"safeMode": false`, and a project config can switch it on but never back off.

Two consequences worth knowing. The narrowing is **unconditional**: `-c` outranks the
project config for the model it picks, but cannot undo a cap the project put on its files.
And what the *user* grants at a permission prompt is **not** narrowed: the human is the
authority, so the agent can still ask for something no config pre-authorised (unless a rule
also says `locked`). Since every narrowing is a minimum, an "or" or a union, the order in
which it is applied does not matter; only the granting layers care about theirs.

`.gitignore` comes last and is not a permission at all: the policy decides access first,
then `respectGitignore` hides ignored paths from listings (see
[file permissions](#file-permissions)). It can only hide, and nested `.gitignore` files
hide more the deeper you go, which mirrors the layering.

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
{} }` is enough) plus the settings that apply to that model alone: `webSearch`, `reasoning`,
`thinking`, `reasoningSummary`, `maxTokens`, `contextWindow`, `temperature`, `webSearchVersion`.

`contextWindow` is the model's limit in tokens, as a number or with a suffix (`200000`,
`"256k"`, `"1m"`, `"1.5m"`; `k` = 1000, `m` = 1000000). When the conversation would no longer fit,
the oldest exchanges are dropped from the history before the next request (a cut always
starts at a user message, so no tool result loses its call; the current request is always
kept), the model gets a `[context notice]` saying how much is gone, and the terminal warns
you; what was shown stays in your scrollback. Sizes are estimated from characters and
calibrated against the token counts the provider reports, so leave the limit at the model's
real window rather than padding it. Unset means never cut. (Compaction, summarising the
dropped part instead of dropping it, is on the list.)

A provider may list no models at all: an endpoint written down ready to use, which you or a
later layer fills in. A model's `name` may contain a slash (`"name":
"anthropic/claude-sonnet-4.5"` on OpenRouter); only the *alias* may not, since that is what
`provider/alias` splits on.

A model is named by its **alias** (`"model": "claude"`, `/model sonnet`), or by
**`provider/alias`** when two providers use the same alias; then the bare alias is refused
with both candidates named. `/models` lists every model with the name that identifies it.

One vendor reachable through two protocols is two providers (an `openai` entry with
`"api": "openai-responses"` and an `openai-chat` entry with `"api": "openai"`), since the
protocol belongs to the endpoint.

`api` is one of

* `anthropic`: Messages API (official Java SDK). `webSearch: true` adds the server-side
  `web_search` tool (`web_search_20260209`; set `"webSearchVersion": "20250305"` for older
  models). Adaptive thinking is on unless `"thinking": false`; `reasoning` maps to
  `output_config.effort` (`low|medium|high|xhigh|max`).
* `openai-responses`: Responses API; also works with other vendors that implement it (e.g.
  DeepSeek: `"baseUrl": "https://api.deepseek.com"`). `webSearch: true` adds the built-in
  `web_search` tool. `reasoning` maps to `reasoning.effort`; `"reasoningSummary": "auto"`
  asks OpenAI to stream reasoning summaries (shown as thinking; DeepSeek streams its
  reasoning without it).
* `openai`: Chat Completions; also the adapter for any OpenAI-compatible server (Ollama,
  vLLM, LM Studio, OpenRouter …) via `url`. `webSearch: true` sets `web_search_options`
  (only search-enabled models accept it).

  For both OpenAI-shaped adapters, `"thinking": true|false` sends the vendor thinking
  switch `"thinking": {"type": "enabled"|"disabled"}` (DeepSeek, GLM, Kimi, MiniMax); leave
  it unset for OpenAI itself, which rejects the parameter. Calls that should not think
  (the next-input prediction) send `disabled` when the switch is configured, otherwise the
  lowest `reasoning_effort` the model family accepts (`none`, `minimal` or `low`; nothing
  for models not known to reason).
* `echo`: the key-less local model used for smoke tests.

**API keys** never sit in a config. A provider names the variable that holds its key:

```json
"deepseek": { "api": "openai-responses", "url": "https://api.deepseek.com",
              "key": "${DEEPSEEK_API_KEY}", "models": { … } }
```

The values live in `.atc/keys.properties`, a Java properties file with one `NAME=value` per
line:

```properties
DEEPSEEK_API_KEY=sk-…
OPENROUTER_API_KEY=
```

A `${VAR}` (or a `keyEnv` name) is looked up in the project's `.atc/keys.properties`, then
`~/.atc/keys.properties`, then the process environment. **An empty value is not a binding**:
the lookup passes over it and carries on, so blanking a line falls back to the next source
instead of breaking. The file is read by `java.util.Properties`, so `#` and `!` comments,
`NAME: value`, `\` escapes and line continuations all work as in any `.properties` file.

The starting `~/.atc/keys.properties` is written beside the global config when you accept
the first-run offer, with an empty line for each variable the starting config names. `atc --init` adds a
`.atc/.gitignore` holding `keys.properties`, and the starting policy makes `.atc` `none` and
`locked`, so the agent can read neither the bindings nor the config. `/config` lists which
variables are bound and from where, never their values.

Two roles. **`model`** is the agent; it never sees classified data. **`classifiedModel`** is
the model that handles `Classified` values through `chat(Classified[String])`; point it at
something you trust with your secrets, typically a local model; leave it unset and classified
data never reaches any model. Both are switched for the session with **`/model [ref]`** and
**`/classifiedmodel [ref]`**: with a reference they switch directly, without one they open a
pick-list of every configured model (`/classifiedmodel off` unsets the role). The
conversation survives a switch, since the history is provider-neutral. When the working directory
has its own `.atc/config.json` the choice is also written there (`"model"` / `"classifiedModel"`,
`null` for `off`; the rest of the file is left as it is), so the next run there starts with it;
a `-c` file that sets the same key still wins.

Layers merge per provider: a project config can add a model to a provider the global config
defined without repeating its `url` and `key`, and a redefined alias replaces that model
entry outright.

### File permissions

Each rule has a `path` pattern and any of `access` (`none|read|write`), `classified` (bool),
`locked` (bool). Patterns are gitignore-flavoured, as in TACIT:

* no `/` in the pattern → matches any path **component** anywhere (`.env`, `*.pem`,
  `node_modules`); use `./name` for a project-relative directory;
* relative with `/` → relative to the working directory (to the project's folder in a
  project config), with `*`, `**`, `?`, `[…]`;
* absolute or `~/…` → absolute; `.` is the working directory (or project) itself.

A rule applies to the path it matches **and its whole subtree**. The effective access of a
path is the **minimum** over all matching rules (a path matched by no rule with an access
level is inaccessible); it is **classified** if any matching rule says so, and **locked** if
any does. So a sub-folder inherits its parent's permission and can only be made stricter:
`build/generated: write` under `build: read` still yields `read`. A rule from the project
layer grants only inside its own project and is a cap everywhere else, as described under
[layers](#layers).

There is no default compiled into the program: the base rules are whatever
`~/.atc/config.json` says. The [starting one](app/resources/atc/config-template.json)
grants no path at all (the [project config](app/resources/atc/project-template.json) that
`atc --init` writes is what opens a project), classifies the usual credential paths
(`.ssh`, `.gnupg`, `.env`, `.env.*`, `.netrc`, `.npmrc`, `.pypirc`, `.docker`, `.kube`,
`.aws`, `.azure`, `.gcloud`, `*.pem`, `id_rsa`, `id_ed25519`), and sets **`.atc` to `none`
and `locked`**: the configuration decides what the agent may do, so the agent can neither
read nor write it and no prompt can open it. That pattern has no `/`, so it covers `~/.atc`
and any project's `.atc` alike. Edit the file to change any of this; delete a rule and it is
gone.

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
allows `ls -la` but not `lsblk`, and `"git diff"` allows `git diff HEAD` but not
`git difftool`). A command also needs read access to the directory it runs in (the working
directory by default), which must not be classified; that check goes through the
`FileSystem` capability, so a `requestFiles` block covers it. Beyond that, a pre-approved
command runs with your privileges and is *not* subject to the file rules (`git diff
--no-index a b` reads any two files, `git log --output=f` writes one), so pre-approve the
subcommands you mean rather than `git *`. `hosts` are glob patterns on host names; only
`http`/`https` URLs are accepted.

`denyCommands` and `denyHosts` are the denylist to those allowlists, with the same pattern
syntax. A matching command or host is refused outright: **deny wins over every allow rule**,
over a session grant, over an open `request*` scope and over `--approve-all`, because it is
checked where the effect happens. A `requestExec`/`requestNetwork` that would permit
something denied fails immediately, without a pop-up, so `"denyCommands": ["git push*"]`
refuses both `exec("git", List("push"))` and `requestExec(Set("git *"))`, and the agent is
told the refusal is final rather than being pointed at a `request*` block. Deny patterns
also *extend* across config layers, so a project config can add to the global denylist but
never drop from it. They are listed in `/perms` and in the agent's system prompt.

## The terminal

`/help`, `/model [ref]`, `/classifiedmodel [ref]`, `/models`, `/mode [name]`, `/perms`, `/todos`, `/config`,
`/interface` (print the API the model sees), `/run [code]` (run Scala in the sandbox
yourself, against the same API, givens and permissions as the agent; the block is shown
like an agent tool call, and since the REPL is shared the agent is told what you ran and
what came of it on its next turn), `/new` (start over: fresh REPL, and the
conversation, TODO list and every "allow for this session" grant are forgotten; mode and
models stay), `/reset` (fresh REPL, conversation kept), `/clear` (forget the conversation,
REPL kept), `/cost`, `/quit`. Ctrl-C interrupts the
current turn including the running snippet (also a `/run`), Shift-Tab cycles the mode on an empty prompt,
Ctrl-O toggles the expanded view, Ctrl-D exits. Tab completes a slash command and its
argument (`/mo`⇥, `/model an`⇥, `/mode `⇥), by plain string matching.

Multi-line input: Shift+Enter or Alt+Enter inserts a newline (where the terminal reports
those keys: kitty, Ghostty, WezTerm, foot, an iTerm2 or VS Code set up to send `\`+Enter
for Shift+Enter, as Claude Code's terminal setup does), as does a `\` typed at the end of
the line before Enter; a pasted block keeps its newlines; a `/run` continues while a
bracket, string or comment is still open, indented like a REPL; and a bare `/run` reads a
whole block. In every case Enter on an empty line submits and Ctrl-C clears. `/cost` counts every
model call since the start (or the last `/clear`/`/new`): the agent's turns, `chat()` and
`chat(Classified)` from the sandbox, and the next-input predictions, each shown separately
when more than one kind occurred.

After each turn the agent model is asked to guess your next request, and the guess appears
as faint ghost text at the prompt: Tab or → accepts it (also once you have typed the start
of it), typing anything else replaces it. It is one small extra model call per turn;
`"predictInput": false` in the config turns it off. The classified model is never used for
this, the guess is made only from the conversation the agent model already saw, it is asked
for without thinking (Anthropic: thinking disabled; OpenAI: the lowest reasoning effort the
model takes), and its tokens show up in `/cost`.

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
a pipe) there are no colours, menus, live windows or folding; everything is printed in
full, and pop-ups fall back to a plain `answer>` line. `ATC_ASCII=1` uses ASCII glyphs.

## Project layout

| Module | What it is |
|--------|------------|
| `lib`  | The one API the model programs against: `atc.lib.Interface` plus the capability and data types (`FileSystem`, `Classified`, `Todo`, …), compiled with capture checking, every agent-visible definition `@assumeSafe`. No implementation lives here; the sandbox injection point (`atc.lib.Runtime`, holding `current` and the root capabilities) sits in its own file, outside the API the model reads. |
| `app`  | The agent program. `atc.host.Host` **implements `Interface` directly** (permission policy, file/process/network effects, questions, TODO list, LLM calls), and the REPL preamble binds that implementation as `api`, so a call in agent code is a plain method call on the host, with no marshalling layer to keep in sync. Also: sandbox and REPL management, LLM providers, terminal UI. |

Built with [Mill](https://mill-build.org) on the Scala 3
nightly version.

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
atc                the wrapper: installs, updates and runs the release jars (tests/atc_test.sh)
start.sh           builds a checkout and runs it
```

A few behaviours that are easy to miss when reading the code: there is one REPL session per
conversation (definitions persist across turns until `/reset` or a mode switch); a runtime
error in agent code does not fail the REPL (it prints the trace), so the session learns
from the REPL's renderer that the code threw and reports it to the model as an error, with
host stack frames trimmed and hints appended for common capture-checking stumbles; and the agent loop resumes by
itself when a provider cuts a response after a server-side tool call (web search, Anthropic
`pause_turn`), nudging the model at most twice per turn when it ends a turn on "Let me …"
without acting.

## License

Apache-2.0.
