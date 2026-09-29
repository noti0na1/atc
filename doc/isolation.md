# System isolation

This document describes the planned system-level isolation for ATC: how the language-based
capability model is combined with operating-system sandboxing and change recovery. It is a
design document for work in progress. As each part lands, its user-facing behavior moves
into the [README](../README.md) and its implementation notes into the
[development guide](development.md); the [plan](#plan-and-status) records what exists.

## Goal

ATC currently guarantees that the Scala the model writes can perform only the effects its
capabilities allow, on the files, commands and hosts the policy permits. The target adds
levels of protection that hold for everything the agent does, through Scala or through any
process it starts, each resting on different parts of the system:

1. The agent cannot act outside the authority the user granted.
2. Every change it makes to the workspace can be reviewed and undone.
3. The capability types describe that authority precisely, and the operating system
   enforces it for code the type system cannot see.

## Threat model

The model is untrusted, together with everything that reaches its context: file contents,
web pages, command output, dependencies, and the build and test code it runs. The user, the
configuration, the host process, the JVM and the kernel are trusted.

The compiler stays in the trusted base: capture checking and safe mode give the precise
guarantees (per-snippet capability use, `Classified` purity, scoped capabilities) that no
other level can. The other levels give coarser guarantees that rest on other parts, the host
and the OS sandbox, so that where levels overlap, a fault in one is limited by the others.

Out of scope: kernel exploits, hardware side channels, timing and termination channels of
classified computations (already excluded for `Classified.map`), and a configuration that
grants too much.

The assets are the integrity of the project and of files outside it; secrets (API keys, SSH
and cloud credentials, classified files); persistent machine state that other programs later
execute (shell startup files, VCS hooks and configuration, editor task files, tool caches);
external accounts reachable over the network; and availability (disk, CPU, stray processes).

## Gaps in the current design

| Concern | Current behavior |
|---|---|
| Damage to files | `FileChange` shows bounded previews of the agent's own file operations. Nothing records changes made by commands, and nothing can undo a change the policy allowed. |
| Commands | A permitted command runs with the user's full authority. Arguments are not checked as paths, so a permitted command can read classified files. Local mode offers no network to Scala, but commands keep full network access. Build files and tests are arbitrary code. |
| One level for agent code | The REPL runs in the host JVM beside the API keys, the configuration and the terminal. JDK classes such as `ProcessBuilder` resolve through the platform loader; safe mode and the lexical validator are the only barriers, so the language level alone stands between agent code and the host's authority. |
| Path races | The host checks a canonical path and then operates on it. Once processes change the workspace concurrently, a check can be raced while the host holds full authority. |

## Layers

| Layer | Property | Covers | Relies on |
|---|---|---|---|
| L0 static capabilities (existing) | A snippet or closure performs effects only through the capabilities it holds; `Classified` stays pure; scoped capabilities do not escape | Precision per snippet and closure, secrets, lifetimes | Compiler soundness |
| L1 evaluator process | The agent's code reaches the machine only through the host's checked operations, enforced by the OS as well | A fault at the language level | The OS sandbox and the host |
| L2 command confinement | A process has at most the authority of the capabilities passed to `exec`, intersected with the policy | Commands, interpreters, build and test code | The kernel sandbox |
| L3 checkpoints | Every workspace change is recorded and can be reverted | Mistakes, and damage the policy permits | The checkpoint store |

L0 is the specification and gives precision; L1 and L2 enforce it, including for code the
type system does not see; L3 covers what a policy cannot prevent. With L2, every OS-level
effect of a snippet, including those of the processes it starts, is contained in the
authority of its capture set intersected with the policy. With L1, the OS also holds the
agent's code to the policy, so a fault at the language level is limited to what the policy
allows.

SHILL (OSDI 2014) is the closest precedent: its `exec` takes capabilities and the child's
sandbox is exactly their image. The TACIT paper names the combination with system sandboxing
as future work.

### L0: static capabilities

Unchanged. [The capability design](development.md#the-capability-design) describes it.

### L1: evaluator process

The compiler, the REPL and agent code move into a child JVM; the host keeps the terminal,
configuration, keys, model clients, `Policy`, checkpoints, the process launcher and the
network proxy. The child's `Interface` implementation forwards every call to the host over a
pipe, and the host checks each request as it does today.

The child runs under a fixed deny-by-default OS profile: it may read the JDK, ATC's jars and
its own working directory, it writes nothing, it has no network, and it starts with an empty
environment. The compiler runs in the child too, so that code executed at compile time is
confined like the rest.

Design rules:

- The channel is untrusted input: frames have a size limit, the session starts with a
  handshake, and JVM logging stays off the channel (a JVM warning printed on stdout was read
  as a 1.5 GB frame length during the spike).
- Capabilities cross as a kind and a scope ID; the host rebuilds them. Each scope is bound to
  the conversation (the logical call stack) that opened it, so a compromised child cannot use
  another conversation's scope. `FileSystem` and `FileSystem^{fs.rd}` are the same object at
  run time, so authority must never be inferred from an erased view.
- A thread that calls out serves nested calls from the host on the same conversation, so a
  `requestFiles` callback runs on the thread that the stop flag and interruption reach.
  Parallel tasks are separate conversations.
- Interruption first uses the existing instrumentation; after a grace period the host kills
  the child and starts a new one, and the model is told its definitions are gone.

Residual risk: classified content has to enter the child for `Classified.map`, so a
compromised child can print it. Classified confidentiality stays protected by the compiler
only.

### L2: command confinement

Every stage of every command runs under a sandbox generated at launch from the capabilities
passed to `exec`, the current scope's grants and the policy. The policy is first rendered as
concrete lists (readable roots, writable roots, masked paths, network mode), which a backend
turns into a Seatbelt profile (macOS), bubblewrap arguments (Linux) or `srt` settings
(Windows).

| Capability passed to `exec` | Process authority |
|---|---|
| `FileSystem` (read-only view) | Read the policy's readable paths and a read-only toolchain bundle; write only a private temporary directory, discarded afterwards |
| `FileSystem^` | Also write the policy's writable paths, except locked, read-only, classified and protected paths |
| A `requestFiles` scope | The scope's grants, applied at launch |
| No `Network` | No network at all |
| `Network^` | Only the host's proxy, which enforces `hosts` and `denyHosts` |

Rules for every process:

- Reads are denied by default and allowed for the project, the policy's readable paths and a
  toolchain bundle detected from installed tools (JDK, build tool homes, dependency caches,
  Homebrew). Classified paths, no-access paths and `~/.atc` are never readable.
- Tool caches are readable but not writable, because unsandboxed tools later load code from
  them. Commands that may write get a cache directory owned by the sandbox (in isolate mode
  one beside the copy); read-only and sealed commands keep caches in their temporary
  directory. Each launch gets a short private `TMPDIR`, and JVMs get `java.io.tmpdir` through
  `JAVA_TOOL_OPTIONS`.
- Unix sockets and loopback TCP are blocked, and the network namespace is always separate on
  Linux. A daemon started outside the sandbox (a build server, an IDE server, a credential
  agent) acts with the user's full authority, so it must be unreachable. Builds run without a
  daemon or start their own inside the sandbox.
- Protected paths are not writable even inside writable roots: `.git` (a git grant makes it
  writable except `hooks`, `config` and `info/attributes`), `.atc`, `.vscode`, `.idea`,
  `.envrc`, and shell startup files. Other programs execute these files outside any sandbox.
- On macOS, LaunchServices, the pasteboard and the keychain services stay denied; a profile
  that allowed mach services by default let a sandboxed command start an application outside
  the sandbox.
- The process tree ends with the command. Linux uses a PID namespace and `--die-with-parent`;
  on macOS a wrapper runs the command in its own process group and kills what is left of it
  when the command exits.
- The environment is scrubbed as today, plus variables that point to credential agents.

The command allowlist applies to commands that may write or use the network. It limits which
programs run, not what they read: any reader it admits (`cat`, `rg`, `git`, an interpreter)
reads whatever the sandbox lets it read, so the sandbox's read roots are what protect files.
A read-only command without network therefore runs any program `denyCommands` does not
refuse, in every mode, and its read roots are narrower: no git user configuration (git gets
`GIT_CONFIG_GLOBAL=/dev/null`) and no `PATH` directory under the home directory outside the
named toolchain roots.

Read-only audit (September 2026, macOS 27 and Ubuntu 24.04 with JDK 17), with read-only
commands started through ATC's own launch path:

- Denied on both: writes outside the private temporary directory, classified and hidden
  paths, the network, loopback TCP, abstract Unix sockets, signals to and the arguments and
  environment of other processes. macOS also denies the home directory beyond the toolchain,
  `/Users`, Unix sockets, the system resolver, the pasteboard, `launchctl submit`,
  `defaults write`, `log show` and Spotlight, and the `TIOCSTI` ioctl.
- Fixed by the audit: a macOS command could open the user's other terminals, reading what
  they typed and writing escape sequences to them; it now reaches only terminals it opens.
  On Linux, bubblewrap mounted all of `/`, so other users' homes, `/srv`, `/mnt` and `/var`
  were readable and Unix sockets under `/run` or `/var/lib` accepted connections (a local
  database, D-Bus); only system directories are mounted now. `/opt/homebrew/var` and
  `/usr/local/var` are hidden on both.
- Still readable beyond the file API: system directories (`/etc`, `/Library/Preferences`,
  `/opt/homebrew/etc`, whose OpenSSL certificates tools need) and the toolchain bundle. For
  commands that may write, the bundle includes `~/.gitconfig` (it can hold tokens) and every
  `PATH` directory under the home directory (20 of 21 here, personal script directories and
  other programs' files among them); read-only commands get neither. `~/.config/git/credentials`,
  a git credential store, was readable and is hidden now.
- Still allowed on macOS: posting Darwin notifications. Commands that may write can also create
  POSIX shared memory and write a segment of another of the user's programs whose name they
  know and whose permissions allow it (the system's own segments are refused); read-only and
  sealed commands get no POSIX shared memory or semaphores, which would outlive them.
- Fixed after the audit: every command could write the sandbox's shared cache, so a
  sealed command could leave a classified file's content there for a later command, and an
  isolate command could change caches a later session in the project uses.
- Unbounded: disk (macOS) or memory (Linux) used by the private temporary directory.

API changes that follow from the table: `exec` keeps requiring `FileSystem^`; a read-only
variant lets read-only mode run commands that write nothing; network comes from a derived
block such as `withNetwork { exec(...) }` whose runtime object carries the flag and whose
capture set records `net`. Commands on classified input run inside a `classified` block
([The agent API with the sandbox](#the-agent-api-with-the-sandbox)).

Backends:

- macOS: `/usr/bin/sandbox-exec -p <profile>` with parameters passed as `-D`. The profile is
  deny-by-default and imports `system.sb`. Missing parameters fail with a misleading
  "unsupported syntax" error.
- Linux: bubblewrap with read-only mounts of the system directories, `--dev /dev`,
  `--proc /proc`, a private `/tmp`, writable binds, masks after the binds (`--tmpfs` plus `--remount-ro` for directories,
  `--ro-bind /dev/null` for files) and again at the project's path after isolate mode's mount
  of the copy there, `--unshare-net --unshare-pid --unshare-user
  --unshare-ipc --die-with-parent --new-session`. Protected paths that do not exist need
  placeholder mounts, which leave empty entries to remove afterwards. A seccomp filter that
  denies `socket(AF_UNIX)` is a strict option because it breaks Python multiprocessing and
  JVM attach. The launch must come from a long-lived Java thread: the parent-death signal
  fires when the starting thread exits. ATC probes at startup whether user namespaces work.
- Windows: Anthropic's sandbox runtime (`srt`) through one long-running helper process per
  session, since the `srt` CLI costs 170 to 250 ms per command (Node start-up, module loading,
  proxy and monitor set-up). It needs a one-time elevated install and runs commands as a
  dedicated user.

### L3: checkpoints

ATC records the workspace in a separate git object store before and after each tool call and
offers to revert the agent's changes after a turn. The store lives in
`~/.atc/checkpoints/<project>`, outside the project and outside every sandbox; it borrows the
project's objects through `objects/info/alternates` when the project is the root of a git
work tree, and keeps its own index as a stat cache.

- Recorded: tracked and untracked files that git does not ignore. Not recorded: ignored
  files, new files over 2 MiB, nested repositories and submodules, classified paths and paths
  under locked no-access rules (such as `.atc`).
- Each call has a pre and a post snapshot. The agent's changes are the union over the turn's
  calls of the differences between them; changes between calls count as the user's.
- Revert, per path, compares the current state C with the agent's final state B and the state
  before the first call that touched the path, A: when C equals B it restores A (or deletes
  the file); when C equals A there is nothing to do; text files the user edited afterwards get
  a three-way merge with B as the base, written only when clean; anything else is reported as
  a conflict and left untouched. Deletions go first; nothing is written through a symlinked
  parent directory.
- The store's configuration makes git store exact bytes (no filters or line-ending
  conversion), disables case folding so case-only renames are visible, and disables the file
  system monitor, which could run a configured program. Paths ATC's own file operations wrote
  are rehashed regardless of stat data, since git compares timestamps at one-second
  granularity.
- The store keeps the newest 50 turns and never runs automatic garbage collection.

Linux can go further: a bubblewrap overlay at the project's real path keeps a command's
writes in an upper directory until they are applied. Build output directories and `.git` must
stay outside the overlay, and renaming an existing directory fails with `EXDEV`. macOS and
Windows have no unprivileged equivalent at the real path, and staging in a copy at another
path is rejected: it forced a full Mill rebuild (21 s against 1.9 s) and editable Python
installs would test the original sources.

## What limits a fault in one level

| Fault | Still holds |
|---|---|
| A fault in capture checking or safe mode | L1 policy, L2, L3 |
| A malicious permitted command or test | L2, L3 |
| A policy that grants too much | L3 review and revert, protected paths |
| No OS sandbox (Windows before `srt`, Linux without user namespaces) | L0, L1, L3; commands run unconfined and ATC says so |
| A damaged checkpoint store | L0 to L2 |

## The agent API with the sandbox

A call's authority is the capabilities it holds. For code the compiler sees, capture checking
enforces it; for code it cannot see (commands, and the evaluator itself), the host turns the
same capabilities into an OS profile. Views such as `fs.rd` are erased at run time, so an
authority the host enforces must live in a distinct runtime object, created by a block or a
function whose signature states what it grants (`requestFiles`, `withNetwork`,
`execReadOnly`). The signature fixes the most a call may do; the runtime object fixes what it
does.

### Classified blocks

`classified { ... }` runs a block and returns its result as `Classified[T]`. (`sealed`, the
first name, is a Scala keyword.)

```scala
val digest: Classified[String] = classified {
  val env  = read(".env")                                          // plain text inside the block
  val keys = execReadOnly("jq -r .token secrets/ci.json").stdout   // a sealed command
  classifiedChat(s"Which of these tokens are expired? $env $keys")
}
println(digest)   // the user sees it; the model sees Classified(***)
```

```scala
def classified[T](using FileSystem, Exec^)(op: (Sealed^, FileSystem, Exec^) ?->{any.rd} T): Classified[T]
extension [T](c: Classified[T]) def reveal(using Sealed^): T
```

- The block receives a sealed, read-only file system and a sealed `Exec^` as context
  parameters, which take precedence over the ambient ones, as in `requestFiles`. From
  outside it may capture only read-only views, so printing, asking, the normal model,
  permission requests, writes, `exec` and the network do not compile.
- `c.reveal` opens a `Classified` value inside the block, through a `Sealed` token that exists
  only there.
- The sealed file system reads classified files as plain text, lists and searches classified
  directories, and changes nothing: the host refuses every write in a sealed scope.
- A sealed command may read classified files, has no network, writes only a scratch
  directory, needs no command pattern, and ends with its children before the block goes on.
  The host refuses `spawn` on a sealed `Exec` and lists no running process to a sealed scope,
  which the types cannot exclude, and refuses permission requests from one.
- An exception or a transfer of control (a non-local `return`, `Breaks.break`) that leaves the
  block becomes a classified failure, as in `Classified.map`; fatal errors and interruption
  escape it.
- Classified content never gets network access. Its destinations are the user (`println`),
  classified files (`writeClassified`) and the classified model (`classifiedChat`).

Nothing a block does persists. An earlier design let it write classified paths, but control
flow inside a block can depend on the secret, and a classified file's existence is visible
outside it, so creating, deleting or changing a file there would leak a bit per file.
`writeClassified(path, value)` stays the way to keep a result: it runs outside any classified
computation and creates its file whether or not the value failed.

The block replaces `readClassified`, `childrenClassified`, `walkClassified`, `flatMap`, `zip`,
`classifiedChat` on a classified value and the planned `execClassified`. `httpPostClassified`
and the `secretHeaders` overloads are removed. Where commands cannot be confined, sealed
commands are refused and pure code in the block still runs.

The typing was prototyped in the real REPL first, and `CapabilitySuite` now checks it:
reads through the block's file system, `reveal`, `execReadOnly`, local mutable state and a
nested block compile; `println`, `ask`, `chat`, `httpGet`, `requestFiles`, writes and `exec`
in the block, the ambient `fs` and `ex`, outer mutable state, the token, the block's file
system or a closure over it leaving the block, and `reveal` outside a block do not.

### Interface cleanup

- `access` and `FileEntry` left the agent API; the host keeps `FileEntryImpl` as its
  internal handle. Agents used the path functions: in 13 saved sessions (135 snippets) no
  `FileEntry` navigation appeared, nor any network, `spawn` or classified call.
- The classified variants went with the block, and `exec(command, args, workingDir)` with
  `ExecOptions(workingDir = ...)`: commands and network take 25 methods instead of 32. The
  remaining overloads are the familiar call shapes; folding `args` into a data value would
  change the most used one for a few lines of prompt.
- Each authority has one form: a block for a scoped or derived capability, a function name
  only where the static view decides (`exec` against `execReadOnly`).

Directions the combination opens, not scheduled: a process lives no longer than the
capabilities it was started with, as `spawn` inside `requestExec` already does; `fs.within(dir)`
as a narrower runtime capability, so that `parallel` tasks given disjoint regions cannot race;
and a revertible block with no network in scope, confined commands and a checkpoint restored
on failure.

### Modes and the auto switch

A mode chooses what the agent can reach; a separate switch chooses whether ATC asks.

| Mode | The agent can |
|---|---|
| isolate | work on a copy of the project with more freedom (any readable file there writable, `.git` included, any command without asking) and nothing outside it writable, without network; changes reach the project when the user reviews and applies them (`/apply`) |
| read-only | read files and run commands that write nothing |
| local | also write files and run commands that write; no network |
| full | also reach allowed hosts |

`auto` rejects every permission request without asking, in every mode: files outside the
policy, commands outside the allowlist, and hosts outside `hosts`, which the command proxy
rejects as well. The agent is told why; the rejected requests are listed for the user at the
end of the turn, to grant for the next one. Deny lists and locked rules are unchanged.

Isolate keeps one copy per project at a fixed location (an APFS clone on macOS, a reflink or
plain copy on Linux), kept between sessions so that build caches stay warm; the session moves
to the copy, and the original project is locked out of it. macOS gives unprivileged programs
no per-process view of the file system: there are no mount namespaces and no bind mounts,
Seatbelt only allows or denies, and `DYLD_INSERT_LIBRARIES` is removed for system programs and
ignored by hardened ones. So commands see the copy at its own path, and caches keyed by path
rebuild once there (Mill 21 s against 1.9 s). No other agent keeps the real path on macOS
either: AgentFS mounts its overlay through a localhost NFS server at a separate path, and
worktree tools accept the new path. On Linux, bubblewrap mounts the copy at the project's
path as well and starts commands there, so they read and write the original path while the
copy takes the changes. Applying uses the checkpoint merge, so edits the user made
meanwhile are kept (see [Isolate mode](development.md#isolate-mode)).

## Platform support

| Layer | macOS | Linux | Windows |
|---|---|---|---|
| L1 evaluator | Seatbelt profile | bubblewrap or Landlock (to do) | AppContainer or low integrity (unverified) |
| L2 commands | Seatbelt | bubblewrap | `srt` helper |
| L3 checkpoints | git store | git store; overlay staging later | git store |

## Plan and status

| Phase | Content | Status |
|---|---|---|
| 0 | This document | Done |
| 1 | Spikes: macOS profiles, Linux bubblewrap, checkpoint store, evaluator process | Done (September 2026) |
| 2 | L3 checkpoints, turn summary, `/undo` | Done; see [Checkpoints](development.md#checkpoints) |
| 3 | L2 on macOS and Linux, local mode without network for commands, protected paths | Done; see [Command sandbox](development.md#command-sandbox) |
| 3b | Host proxy that enforces `hosts` for commands in full mode | Done |
| 4 | Capability-derived process authority in the API: `execReadOnly`, `withNetwork` | Done; `execClassified` becomes the `classified` block (phase 8) |
| 5 | L1 evaluator on macOS and Linux | Done; see [Evaluator process](development.md#evaluator-process) |
| 6 | Windows through `srt`, Linux overlay staging, a discovery mode that logs what a run needed | Linux staging done as isolate mode's copy mounted at the project's path (phase 11); Windows through `srt` and the discovery mode not started: `srt` on Windows needs a Windows machine and its elevated install to build and verify, and a discovery log has no Linux counterpart to Seatbelt's reports |
| 7 | The `auto` switch | Done; see [Scope lifecycle](development.md#scope-lifecycle) |
| 8 | `classified` blocks; classified network paths removed | Done; see [Classified data](development.md#classified-data) |
| 9 | Read-only mode: whether confinement protects files inside and outside the project well enough to run any read-only command without the allowlist, and the narrower read roots that needs | Done: holes closed (terminals, Linux mounts, service data, git credentials); read-only commands without network run any program, with narrower read roots |
| 10 | Interface cleanup | Done |
| 11 | Isolate mode: one copy per project, applied with a merge; on Linux commands see it at the project's path | Done; see [Isolate mode](development.md#isolate-mode) |

## Spike measurements

Measured on an M2 Pro with macOS 27 and JDK 25, and in a Fedora 43 container under Docker
Desktop for Linux.

| Measurement | Result |
|---|---|
| `sandbox-exec` per launch | 6.5 ms; 15 ms with `system.sb` imported |
| bubblewrap per launch, full flags | 4.2 ms mean |
| Build under the deny-by-default macOS profile | No measurable difference from unsandboxed |
| Checkpoint of ATC (192 files) | 0.2 to 0.5 s first, about 0.1 s after |
| Checkpoint of the Scala 3 repository (25k files) | 1.6 s first with alternates, 14 to 16 s without; about 0.15 s after |
| Revert of 10 changed files | 0.24 s |
| Evaluator start-up, spawn to first result | About 150 ms more than in-process; the sandbox adds nothing measurable |
| Evaluator host call round trip | 13 to 21 µs |
| Stopping a stuck evaluator | Kill in about 10 ms, restart as at start-up |

## Residual risks

- Classified content is protected by the compiler only, with or without L1.
- Committed classified files remain readable through git objects by any process that may
  read `.git`.
- Confined commands read system and toolchain directories the file API refuses, including
  `~/.gitconfig` and `PATH` directories under the home directory for commands that may
  write; on macOS they can post notifications and write another program's shared memory
  when they know its name (phase 9).
- On macOS, a process that detaches from its process group survives the launch, though it
  stays confined.
- On macOS, a confined command can read the arguments (not the environment) of the user's
  other processes through `sysctl`, which no profile rule stops. A sealed command therefore
  runs alone among the agent's commands and not while a spawned process runs, and in
  read-only mode every command runs alone; a process the user started is out of reach of
  that rule.
- On macOS, commands see the size and times of classified files: Seatbelt denies their
  content, and a directory listing returns its entries' attributes in bulk, whatever the
  profile says about each entry. The length of what `writeClassified` writes is therefore
  readable, and a block's running time is observable everywhere.
- Host file operations remain check-then-use; mitigations are no-follow opens, a check after
  opening, and on Linux `openat2` with `RESOLVE_BENEATH`.
- Checkpoints cannot restore ignored files, files over the size cap, or classified files, and
  a user edit made while a command runs is attributed to the agent (the merge limits the
  damage).

## References

- S. Moore, C. Dimoulas, D. King, S. Chong. SHILL: A Secure Shell Scripting Language. OSDI 2014.
- R. Watson et al. Capsicum: practical capabilities for UNIX. USENIX Security 2010.
- N. Provos, M. Friedl, P. Honeyman. Preventing Privilege Escalation. USENIX Security 2003.
- S. Narayan et al. Retrofitting Fine Grain Isolation in the Firefox Renderer (RLBox). USENIX Security 2020.
- N. Zeldovich et al. Making Information Flow Explicit in HiStar. OSDI 2006.
- D. Stefan et al. Addressing Covert Termination and Timing Channels in Concurrent Information Flow Systems. ICFP 2012.
- Anthropic sandbox runtime: https://github.com/anthropic-experimental/sandbox-runtime
- OpenAI Codex sandboxing: https://github.com/openai/codex
- The TACIT paper: https://arxiv.org/abs/2603.00991
