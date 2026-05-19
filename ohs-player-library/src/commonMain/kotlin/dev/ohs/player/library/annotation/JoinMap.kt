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
package dev.ohs.player.library.annotation

/**
 * Declares the ViewJoinMap structure that drives extraction for a generated state class.
 *
 * The [pivot] describes the primary resource type and SearchResult bucket from which one row is
 * produced per resource instance. Each [joins] entry describes an additional resource whose columns
 * are merged into that row.
 *
 * @param pivot Primary resource scope — one output row per pivot resource instance.
 * @param joins Zero or more additional resource scopes whose columns are merged into each row.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class JoinMap(val pivot: Scope, val joins: Array<Scope> = [])
