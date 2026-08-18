package atc

import atc.host.*
import atc.perms.*
import atc.sandbox.*
import java.nio.file.{Files, Path}

/** Dev helper: run each `// ---`-separated snippet of a file in a fresh
  * sandbox session (permissive policy, everything approved) and print results.
  * `args`: file [safe|nosafe] [mode=readonly|local|full | preamble-file]. */
object Scratch:
  def main(args: Array[String]): Unit =
    val root = Files.createTempDirectory("atc-scratch").nn.toRealPath().nn
    val policy = Policy(
      List(FileRule(PathPattern(".", root), Some(Access.Write), None)),
      List("echo", "ls"),
      Nil,
      _ => Decision.AllowSession
    )
    val agentOut = StringBuilder()
    val userOut = StringBuilder()
    var session: Option[ReplSession] = None
    val output = new HostOutput:
      def print(agentText: String, userText: String): Unit =
        agentOut.append(agentText); session.foreach(_.printStream.print(agentText))
        userOut.append(if agentText == userText then userText else s"[C]$userText")
    val llm = new HostLlm:
      def chat(m: String) = s"echo:$m"
      def chatClassified(m: String) = s"safe:$m"
    val hostUi = new HostUi:
      def askUser(question: String, options: List[String], multiple: Boolean): Option[String] = Some("yes")
      def showTodos(items: List[atc.lib.Todo]): Unit = println(s"[todos] $items")
    val host = Host(policy, root, output, llm, hostUi)
    val safe = args.length < 2 || args(1) != "nosafe"
    val third = if args.length >= 3 then Some(args(2)) else None
    val mode = third.filter(_.startsWith("mode=")).map(m => Mode.parse(m.drop(5))).getOrElse(Mode.Full)
    policy.mode = mode
    val preambleOverride = third.filterNot(_.startsWith("mode=")).map(f => Files.readString(Path.of(f)).nn)
    val s0 =
      ReplSession(SandboxConfig(safeMode = safe, mode = mode, executionTimeoutMs = Some(60000)), host, preambleOverride)
        .init()
    session = Some(s0)
    val snippets = Files.readString(Path.of(args(0))).nn.split("(?m)^// ---.*$").nn.map(_.nn.trim).filter(_.nonEmpty)
    for s <- snippets do
      println("=" * 70)
      println(s)
      println("-" * 70)
      val r = s0.run(s)
      println(s"success=${r.success}")
      println(r.output)
      r.error.foreach(e => println(s"ERROR: $e"))
      if agentOut.nonEmpty then { println(s"[agent-visible prints] $agentOut"); agentOut.clear() }
      if userOut.nonEmpty then { println(s"[user-visible prints] $userOut"); userOut.clear() }
