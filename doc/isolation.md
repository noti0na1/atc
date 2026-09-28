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
  them. Tools that must write get a cache directory owned by the sandbox. Each launch gets a
  short private `TMPDIR`, and JVMs get `java.io.tmpdir` through `JAVA_TOOL_OPTIONS`.
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
- The process tree ends with its scope. Linux uses a PID namespace and `--die-with-parent`;
  macOS kills the launch's process group.
- The environment is scrubbed as today, plus variables that point to credential agents.

The command allowlist becomes a statement of intent rather than containment. Whether local
mode may run any non-denied command under confinement is an open decision.

API changes that follow from the table: `exec` keeps requiring `FileSystem^`; a read-only
variant lets read-only mode run commands that write nothing; network comes from a derived
block such as `withNetwork { exec(...) }` whose runtime object carries the flag and whose
capture set records `net`. A later extension, `execClassified`, runs a process on classified
input under a sealed profile (no network, a fresh scratch directory, no IPC) and returns its
stdout, stderr, exit code and timing inside a `Classified` result.

Backends:

- macOS: `/usr/bin/sandbox-exec -p <profile>` with parameters passed as `-D`. The profile is
  deny-by-default and imports `system.sb`. Missing parameters fail with a misleading
  "unsupported syntax" error.
- Linux: bubblewrap with `--ro-bind / /`, `--dev /dev`, `--proc /proc`, a private `/tmp`,
  writable binds, masks after the binds (`--tmpfs` plus `--remount-ro` for directories,
  `--ro-bind /dev/null` for files), `--unshare-net --unshare-pid --unshare-user
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
| 4 | Capability-derived process authority in the API: `execReadOnly`, `withNetwork` | Done; `execClassified` planned |
| 5 | L1 evaluator on macOS and Linux | Done; see [Evaluator process](development.md#evaluator-process) |
| 6 | Windows through `srt`, Linux overlay staging, a discovery mode that logs what a run needed | Planned |

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
- On macOS, a process that detaches from its process group survives the launch, though it
  stays confined.
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
