package atc.agent

/** Why the agent stopped. Finished means the model ended its response, not that every tool succeeded. */
enum TurnOutcome(val label: String, val exitCode: Int):
  case Finished extends TurnOutcome("finished", 0)
  case Interrupted extends TurnOutcome("interrupted", 130)
  case Blocked extends TurnOutcome("blocked", 1)
  case LimitReached extends TurnOutcome("limit reached", 1)
  case Failed extends TurnOutcome("failed", 1)
