package atc.confine

import atc.perms.{Access, PathPattern, Policy, ScopeId}
import atc.platform.{Platform, PlatformPath}

import java.nio.file.{Files, Path, Paths}

/** What a command may reach, as concrete paths: the input a sandbox backend renders.
  *
  * `readable` and `writable` are the roots the policy grants and `toolchain` the
  * toolchain directories (a root covers its subtree); `restrictions` narrow them and
  * are applied after them. Glob restrictions come from the user's file rules and
  * apply within the policy's roots only, not to system or toolchain directories,
  * which hold certificate bundles that `*.pem` would otherwise hide. System
  * directories are the backend's concern. A plan never grants more than the policy: roots come from rules and
  * grants that give access, and every rule that takes access away becomes a
  * restriction, whatever grant might widen it. */
final case class SandboxPlan(
  project: Path,
  home: Path,
  readable: List[Path],
  writable: List[Path],
  toolchain: List[Path],
  restrictions: List[SandboxPlan.Restriction],
  network: Boolean,
  /** A directory owned by the sandbox, where tools keep caches between commands. */
  cache: Path,
)

object SandboxPlan:
  /** How much a restriction takes away. */
  enum Level:
    /** Neither read nor written. */
    case Hidden
    /** Content neither read nor written; the path may be seen (classified data). */
    case Secret
    /** Not written. */
    case ReadOnly

  /** The paths a restriction covers: a path and its subtree, a glob below a real
    * path, or a glob on any single name. */
  enum Target:
    case Exact(path: Path)
    case Anchored(root: Path, glob: String)
    case Component(glob: String)

  final case class Restriction(level: Level, target: Target)

  /** Build the plan for a command started with capabilities of `scope`. `project`
    * and `home` are canonical; `network` is whether the command may use it, and
    * `mayWrite` whether it may write where the policy lets the agent write (otherwise
    * those roots are only readable). */
  def apply(
    policy: Policy,
    scope: ScopeId,
    project: Path,
    home: Path,
    network: Boolean,
    mayWrite: Boolean,
    cache: Path,
  ): SandboxPlan =
    val granted =
      policy.rules.flatMap: rule =>
        rule.pattern.form match
          case PathPattern.Form.Exact(path) if rule.access.exists(_ != Access.None) && rule.grants(path) => Some(path)
          case _ => None
      ++ policy.fileGrants(scope).collect { case (path, access) if access != Access.None => path }
    // In a classified block's scope a command may read classified content (it runs sealed).
    val sealedBlock = policy.sealedScope(scope)
    val roots = granted.distinct.map(path => path -> policy.effective(scope, path))
    val writableRoots = roots.collect { case (path, perm) if perm.canWrite && !perm.classified => path }
    val writable = if mayWrite then writableRoots else Nil
    val readable = roots.collect:
      case (path, perm) if perm.canRead && (!perm.classified || sealedBlock) && !writable.contains(path) => path
    val fromRules = policy.rules.flatMap: rule =>
      val level =
        if rule.access.contains(Access.None) then Some(Level.Hidden)
        else if rule.classified.contains(true) then Option.when(!sealedBlock)(Level.Secret)
        // In isolate mode the copy, the one writable root, has no read-only limits but locked ones.
        else if rule.access.contains(Access.Read) && (rule.locked || policy.copyRoot.isEmpty) then
          Some(Level.ReadOnly)
        else None
      level.flatMap: ruleLevel =>
        rule.pattern.form match
          case PathPattern.Form.Exact(path) =>
            exactLevel(policy, scope, path).filter(level => !(sealedBlock && level == Level.Secret))
              .map(Restriction(_, Target.Exact(path)))
          case PathPattern.Form.Anchored(root, glob) => Some(Restriction(ruleLevel, Target.Anchored(root, glob)))
          case PathPattern.Form.Component(glob) => Some(Restriction(ruleLevel, Target.Component(glob)))
    val protectedPaths = (project :: writable).distinct.flatMap(protectedIn(policy, scope, _))
    val serviceData =
      ServiceData.map(Paths.get(_).nn).filterNot(data => (readable ++ writable).exists(_.startsWith(data)))
        .map(data => Restriction(Level.Hidden, Target.Exact(data)))
    val homeRules =
      HomeSecrets.map(name => Restriction(Level.Hidden, Target.Exact(home.resolve(name).nn))) ++
        Option.when(!mayWrite)(GitConfig.map(name => Restriction(Level.Hidden, Target.Exact(home.resolve(name).nn))))
          .toList.flatten ++
        Option.when(writable.exists(home.startsWith(_)))(HomeStartup.map(name =>
          Restriction(Level.ReadOnly, Target.Exact(home.resolve(name).nn))
        )).toList.flatten
    SandboxPlan(
      project,
      home,
      readable.distinct,
      writable,
      toolchain(home, readOnly = !mayWrite, Option(System.getenv("PATH")).getOrElse("")),
      (fromRules ++ protectedPaths ++ homeRules ++ serviceData).distinct,
      network,
      cache
    )

  /** The restriction an exact rule path has in `scope`, if any: a grant may have widened it. */
  private def exactLevel(policy: Policy, scope: ScopeId, path: Path): Option[Level] =
    val perm = policy.effective(scope, path)
    if !perm.canRead then Some(Level.Hidden)
    else if perm.classified then Some(Level.Secret)
    else if !perm.canWrite then Some(Level.ReadOnly)
    else None

  /** Paths in a writable root that other programs execute later, so no command may
    * change them: VCS metadata, editor settings, environment loaders and ATC's own.
    * When the policy lets commands write `.git`, only its hooks and configuration
    * stay read-only, so that commits work. */
  private def protectedIn(policy: Policy, scope: ScopeId, root: Path): List[Restriction] =
    val git = root.resolve(".git").nn
    val gitParts =
      if policy.effective(scope, git).canWrite then
        GitExecutables.map(name => Restriction(Level.ReadOnly, Target.Exact(git.resolve(name).nn))) ++
          List(Restriction(Level.ReadOnly, Target.Anchored(git, "modules/**/{config,hooks}")))
      else List(Restriction(Level.ReadOnly, Target.Exact(git)))
    gitParts ++ ProjectProtected.map(name => Restriction(Level.ReadOnly, Target.Exact(root.resolve(name).nn)))

  /** Files in `.git` that make git run programs. */
  val GitExecutables: List[String] = List("hooks", "config", "config.worktree", "info/attributes")

  /** Project entries that editors, shells and ATC read and act on. */
  val ProjectProtected: List[String] = List(".atc", ".vscode", ".idea", ".envrc")

  /** Where package managers keep the data of the services they run (databases, logs), inside
    * system directories commands may read; hidden unless a policy root lies inside one. */
  val ServiceData: List[String] = if Platform.isWindows then Nil else List("/opt/homebrew/var", "/usr/local/var")

  /** Credentials and agent sockets in the home directory, hidden even when a root covers them. */
  val HomeSecrets: List[String] = List(
    ".ssh",
    ".aws",
    ".azure",
    ".gnupg",
    ".atc",
    ".config/gh",
    ".config/gcloud",
    ".docker",
    ".kube",
    ".netrc",
    ".npmrc",
    ".pypirc",
    ".git-credentials",
    ".password-store",
    ".config/git/credentials",
    ".cargo/credentials",
    ".cargo/credentials.toml",
    ".ivy2/.credentials",
    ".sbt/.credentials",
    ".sbt/1.0/server",
    ".gradle/gradle.properties",
    ".m2/settings.xml",
    ".m2/settings-security.xml",
    "Library/Keychains",
    ".local/share/keyrings",
  )

  /** Shell and session start-up files, read-only when a writable root covers the home directory. */
  val HomeStartup: List[String] = List(
    ".bashrc",
    ".bash_profile",
    ".bash_login",
    ".profile",
    ".zshrc",
    ".zprofile",
    ".zshenv",
    ".zlogin",
    ".config/fish",
    ".gitconfig",
    ".config/git",
    "Library/LaunchAgents",
    ".config/autostart",
    ".config/systemd",
  )

  /** Git's user configuration. Commands that may write read it (a commit needs the identity);
    * read-only commands do not, since it can hold tokens, and git gets an empty one instead. */
  val GitConfig: List[String] = List(".gitconfig", ".config/git/config")

  /** Toolchains and dependency caches under the home directory that commands may read:
    * JDKs and build tools, their dependency caches, language version managers, and the
    * directories on the `PATH` (`pathVariable`). For a read-only command (`readOnly`), a
    * `PATH` directory under the home directory counts only inside one of the named roots:
    * others hold personal scripts and other programs' files. Only existing directories are
    * listed. */
  def toolchain(home: Path, readOnly: Boolean, pathVariable: String): List[Path] =
    val named = List(
      ".sdkman/candidates",
      ".jdks",
      "Library/Java",
      ".cache/mill",
      ".cache/coursier",
      "Library/Caches/Coursier",
      ".ivy2",
      ".sbt",
      ".m2/repository",
      ".gradle/caches",
      ".gradle/wrapper",
      ".npm/_cacache",
      ".nvm/versions",
      ".cargo",
      ".rustup",
      ".pyenv",
      ".local/share/uv",
      ".local/bin",
      ".local/lib",
      "go/pkg/mod",
      "go/bin",
      ".deno",
      ".bun",
      ".elan",
      ".ghcup",
      ".opam",
      ".juliaup",
      ".volta",
      ".asdf",
      ".rbenv",
      ".local/share/mise",
      ".local/share/coursier",
      "Library/Application Support/Coursier",
      ".conda",
      "micromamba",
      "miniconda3",
      "anaconda3",
      "miniforge3",
    ).map(home.resolve(_).nn)
    val git = List(".config/git", ".gitconfig").map(home.resolve(_).nn)
    val javaHomes = (Option(System.getProperty("java.home")) ++ Option(System.getenv("JAVA_HOME"))).map(Paths.get(_).nn)
    def existing(paths: List[Path]) =
      paths.filter(p => p.isAbsolute && Files.exists(p)).map(PlatformPath.canonical)
    val roots = existing(named)
    val path = existing(pathVariable.split(Platform.pathListSeparator).toList.filter(_.nonEmpty).map(Paths.get(_).nn))
      .filter(dir => !readOnly || !dir.startsWith(home) || roots.exists(dir.startsWith(_)))
    (roots ++ existing(git ++ javaHomes) ++ path)
      // Never the home directory, a directory above it, or a file system root.
      .filter(p => !home.startsWith(p) && p.getParent != null)
      .distinct
