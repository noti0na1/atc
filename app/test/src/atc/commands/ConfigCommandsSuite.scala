package atc.commands

import atc.config.Config

/** The settings `/config` changes: what a typed value means, and how the value in force reads. */
class ConfigCommandsSuite extends munit.FunSuite:
  import ConfigCommands.Setting.*

  test("typed values become config values, and anything else is refused with what is allowed"):
    assertEquals(PredictInput.parse("off"), Right(ujson.False))
    assertEquals(WebSearch.parse(" TRUE "), Right(ujson.True))
    assertEquals(Notifications.parse("Bell"), Right(ujson.Str("bell")))
    assertEquals(AutoCompactThreshold.parse("0.5"), Right(ujson.Num(0.5)))
    assertEquals(CompactKeepRatio.parse("0"), Right(ujson.Num(0)))
    assertEquals(PredictInput.parse("maybe"), Left("predictInput is true or false, not 'maybe'"))
    assertEquals(Notifications.parse("loud").left.map(_.contains("auto | system | terminal | bell | off")), Left(true))
    for bad <- List("1.5", "-0.1", "NaN", "Infinity", "half") do
      assert(AutoCompactThreshold.parse(bad).isLeft, bad)

  test("every offered choice parses, and the value in force reads as one"):
    for setting <- values; choice <- setting.choices do assert(setting.parse(choice).isRight, s"${setting.key} $choice")
    val defaults = Config()
    for setting <- values do assert(setting.choices.contains(setting.current(defaults)), setting.key)
    assertEquals(AutoCompactThreshold.current(Config(autoCompactThreshold = 0.75)), "0.75")

  test("settings are named case-insensitively; policy settings are not among them"):
    assertEquals(named("AUTOCOMPACTTHRESHOLD"), Some(AutoCompactThreshold))
    assertEquals(named("maxToolCalls"), None)
