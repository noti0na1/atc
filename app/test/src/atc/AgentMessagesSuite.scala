package atc

import atc.agent.AgentMessages

class AgentMessagesSuite extends munit.FunSuite:
  test("sandbox restart and pending-note composition preserve the existing protocol text"):
    val restart = AgentMessages.sandboxRestarted("the sandbox mode changed to read-only")
    assertEquals(
      restart,
      "[sandbox notice] The Scala REPL was restarted (the sandbox mode changed to read-only). Every `val`, `def` and `import` " +
        "you defined earlier is gone, so re-create anything you still need. The conversation itself is unchanged."
    )
    assertEquals(
      AgentMessages.userMessage(List(restart, "[another note]"), "do it"),
      s"$restart\n\n[another note]\n\ndo it"
    )
    assertEquals(AgentMessages.userMessage(Nil, "plain"), "plain")

  test("a normal user-ran note is byte-for-byte compatible with the old three-backtick form"):
    assertEquals(
      AgentMessages.userRan("val answer = 42", "answer: Int = 42"),
      "[user ran code] The user ran this in the sandbox REPL themselves (its definitions persist for you too):\n" +
        "```scala\nval answer = 42\n```\nResult:\nanswer: Int = 42"
    )

  test("the user-ran Markdown fence is longer than every backtick run in the code"):
    val code = "val short = \"```\"\nval long = \"`````\""
    val fence = "``````"
    assertEquals(
      AgentMessages.userRan(code, "ok"),
      s"[user ran code] The user ran this in the sandbox REPL themselves (its definitions persist for you too):\n" +
        s"${fence}scala\n$code\n$fence\nResult:\nok"
    )

  test("failure, empty, unsafe and interruption markers preserve their exact text"):
    assertEquals(AgentMessages.turnFailed(RuntimeException("api down")), "[turn failed: api down]")
    val noMessage = new RuntimeException(null: String | Null)
    assertEquals(AgentMessages.turnFailed(noMessage), s"[turn failed: ${noMessage.toString}]")
    assertEquals(AgentMessages.emptyResponse("end_turn"), "[model returned no response; stop_reason=end_turn]")
    assertEquals(
      AgentMessages.unsafeResponse("content_filter"),
      "[content_filter model response; tool calls were not executed]"
    )
    assertEquals(AgentMessages.interrupted, "[interrupted by user]")

  test("tool result and loop-bound messages preserve their exact text"):
    assertEquals(
      AgentMessages.toolBudgetExhausted(7),
      "Tool budget of 7 calls per turn exhausted; answer the user now."
    )
    assertEquals(AgentMessages.cancelledBeforeExecution, "Cancelled by the user before execution.")
    assertEquals(AgentMessages.missingCodeArgument, "Missing 'code' argument.")
    assertEquals(AgentMessages.thinkingStatus("sonnet"), "sonnet is thinking")
    assertEquals(
      AgentMessages.unknownTool("delete_everything", "run_scala"),
      "Unknown tool 'delete_everything'. Only run_scala is available; everything else is a Scala function."
    )
    assertEquals(
      AgentMessages.unsafeToolCallsWarning(2, "refusal"),
      "ignored 2 tool call(s) from a refusal response"
    )
    assertEquals(
      AgentMessages.blockedResponseWarning("refusal"),
      "The model blocked this request (stop_reason=refusal)."
    )
    assertEquals(
      AgentMessages.resumeExhaustedWarning("sonnet", 20),
      "sonnet remained unfinished after 20 resume attempts"
    )
    assertEquals(
      AgentMessages.toolBudgetLoopWarning,
      "model kept requesting tools after exhausting the tool budget; stopping this turn"
    )

  test("context and continuation messages preserve their exact text"):
    assertEquals(
      AgentMessages.contextCutNotice(12),
      "[context notice] The 12 oldest messages of this conversation were dropped to fit your context window; " +
        "if you need something from them, ask the user or read it again."
    )
    assertEquals(
      AgentMessages.contextDroppedWarning("tiny", 1000, 3),
      "context window of tiny (1000 tokens): the oldest 3 messages were dropped from what the model sees"
    )
    assertEquals(
      AgentMessages.contextOverflowWarning(
        "tiny",
        1000,
        AgentMessages.ContextOverflowCause.FixedPrompt,
        500,
        400,
        600,
        Some(600),
      ),
      "context window of tiny (1000 tokens): the system prompt and tool schema alone needs an estimated 500 input tokens, " +
        "but only 400 remain with 600 tokens reserved for configured maxTokens=600; the provider may reject this request. " +
        "Shorten the request or configure a larger contextWindow/maxTokens combination."
    )
    assertEquals(
      AgentMessages.truncationContinuation,
      "[continuation request] Continue exactly where the previous response was truncated. " +
        "Do not repeat completed work; finish the user's original request."
    )
