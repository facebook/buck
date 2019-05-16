/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.multitenant.service

import io.vavr.collection.HashSet
import io.vavr.collection.Set

/*
 * There are a lot of "persistent collections" libraries out there for Java (Vavr, PCollections,
 * Paguro, etc.). Currently, we are using Vavr, but if we later discover that a different library
 * has better performance, works better with Kotlin, etc., we should be able to switch without too
 * much trouble.
 *
 * The simplest thing would be to introduce a new type that wraps a Vavr collection, but that would
 * add an extra object for each instance, which is undesirable. We could introduce an interface to
 * hide the implementation, but apparently you cannot add an interface to an existing class:
 * https://stackoverflow.com/questions/48861009/is-it-possible-to-add-an-interface-to-an-existing-class-in-kotlin
 *
 * Instead, we use a typealias accompanied by a number of extension methods. This should make a
 * future migration tractable without taking up extra memory.
 */

/**
 * A persistent set of build rule names.
 */
typealias BuildRuleNames = Set<String>

fun Sequence<String>.toBuildRuleNames(): BuildRuleNames = HashSet.ofAll(this.asIterable())
