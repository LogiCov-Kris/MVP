package logicov.auth

import scala.collection.mutable

/** Bounds password hashing work and repeated attempts per instance, including unknown accounts. */
object LoginLimiter {
  private val attempts = mutable.Map.empty[String, (Long, Int)]
  private val Window = 15 * 60 * 1000L

  def allow(key: String, now: Long = System.currentTimeMillis()): Boolean = synchronized {
    attempts.filterInPlace((_, value) => now - value._1 < Window)
    val (started, count) = attempts.getOrElse(key, now -> 0)
    if (count >= 10 || (attempts.size >= 10000 && !attempts.contains(key))) false
    else {
      attempts.update(key, started -> (count + 1))
      true
    }
  }
}
