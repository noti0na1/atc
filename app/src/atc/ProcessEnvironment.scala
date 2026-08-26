package atc

import scala.jdk.CollectionConverters.*

/** The process environment boundary. `get` deliberately delegates to the JVM
  * on every call so Windows' case-insensitive variable-name semantics are
  * preserved instead of being lost in a copied Scala map. */
private[atc] object ProcessEnvironment:
  def get(name: String): Option[String] = Option(System.getenv(name))
  def contains(name: String): Boolean = System.getenv(name) != null
  def entries: collection.Map[String, String] = System.getenv().nn.asScala
