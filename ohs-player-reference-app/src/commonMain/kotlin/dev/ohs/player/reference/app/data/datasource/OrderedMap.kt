/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.player.reference.app.data.datasource

/**
 * A string-keyed map backed by a [HashMap] for O(1) lookup and an [ArrayDeque] for O(1) ordered
 * insertion. Supports two insertion modes:
 * - [set] / [append]: adds the key at the tail (used when seeding from static data).
 * - [prepend]: adds the key at the head (used when recording new items so they surface first).
 *
 * Duplicate keys are silently ignored for ordering — the first insertion wins the slot. Updating an
 * existing key with [set] or [prepend] only refreshes the value, not the position.
 */
internal class OrderedMap<V> {
  private val order = ArrayDeque<String>()
  private val index = HashMap<String, V>()

  operator fun get(key: String): V? = index[key]

  /** Appends [key] at the tail. O(1) amortised. */
  operator fun set(key: String, value: V) {
    if (!index.containsKey(key)) order.addLast(key)
    index[key] = value
  }

  /** Prepends [key] at the head so it appears first during iteration. O(1) amortised. */
  fun prepend(key: String, value: V) {
    if (!index.containsKey(key)) order.addFirst(key)
    index[key] = value
  }

  fun containsKey(key: String): Boolean = index.containsKey(key)

  /** Keys in insertion order (head → tail). */
  val keys: List<String>
    get() = order.toList()

  /** Values in insertion order (head → tail). */
  val values: List<V>
    get() = order.mapNotNull { index[it] }

  fun clear() {
    order.clear()
    index.clear()
  }
}
