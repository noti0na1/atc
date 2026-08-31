package atc

import atc.host.Processes

class ProcessesSuite extends munit.FunSuite:
  test("tail output coalesces fragmented appends and truncates only past the cap"):
    val buffer = Processes.TailBuffer(64)
    val text = (0 until 64).map(i => ('a' + i % 26).toChar).mkString
    text.foreach(char => buffer.append(char.toString))

    assertEquals(buffer.peek, text)
    assertEquals(buffer.marker, "")
    assertEquals(buffer.retainedChunkCount, 1)

    buffer.append("!")
    assertEquals(buffer.peek, text.drop(1) + "!")
    assert(buffer.marker.contains("older output dropped"), buffer.marker)

    val continued = (0 until 4096).map(i => ('A' + i % 26).toChar).mkString
    continued.foreach(char => buffer.append(char.toString))
    assertEquals(buffer.peek, continued.takeRight(64))
    assert(buffer.retainedChunkCount <= 2, s"retained ${buffer.retainedChunkCount} chunks")

  test("tail output preserves consume and take semantics across physical chunks"):
    val chunkSize = 8 * 1024
    val cap = chunkSize + 808
    val buffer = Processes.TailBuffer(cap)
    buffer.append("a" * chunkSize)
    List("b" * 400, "b" * 408).foreach(buffer.append)
    assertEquals(buffer.retainedChunkCount, 2)

    assertEquals(buffer.consume(chunkSize + 3), "a" * chunkSize + "bbb")
    assertEquals(buffer.peek, "b" * 805)

    buffer.append("c" * (chunkSize + 3))
    assertEquals(buffer.peek, "b" * 805 + "c" * (chunkSize + 3))
    assertEquals(buffer.marker, "")
    assertEquals(buffer.take(), "b" * 805 + "c" * (chunkSize + 3))
    assertEquals(buffer.peek, "")

    buffer.append("d" * (cap + 1))
    assertEquals(buffer.peek, "d" * cap)
    assert(buffer.marker.contains("older output dropped"), buffer.marker)
    assertEquals(buffer.consume(cap + 10), "d" * cap)
    assertEquals(buffer.peek, "")

    buffer.append("tail")
    assertEquals(buffer.take(), "tail")
