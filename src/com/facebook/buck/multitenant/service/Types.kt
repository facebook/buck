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

import com.facebook.buck.core.model.BuildTarget
import java.nio.file.Path

typealias Commit = String

typealias BuildRules = Map<String, Set<BuildTarget>>

/**
 * For the purpose of this prototype, we currently define the contents of a build package by the
 * names of the rules it contains and the deps of those rules.
 */
data class BuildPackage(val buildFileDirectory: Path, val rules: BuildRules)

internal typealias BuildTargetId = Int

/** The values in the array must be sorted. */
internal typealias BuildTargetSet = IntArray

/*
 * Note that for the following "Internal" types, we aspire to use more memory-efficient data
 * structures (e.g., `int[]` rather than List<Integer>`) because we expect to store many instances
 * of them in memory as part of the Index.
 */

internal typealias InternalBuildRules = Map<String, BuildTargetSet>

internal data class InternalBuildPackage(val buildFileDirectory: Path,
                                         val rules: InternalBuildRules) {
    override fun equals(other: Any?): Boolean {
        if (other !is InternalBuildPackage) {
            return false
        }
        return buildFileDirectory == other.buildFileDirectory && equals_InternalBuildRules(rules,
                other.rules)
    }

    override fun hashCode(): Int {
        return 31 * buildFileDirectory.hashCode() + hashCode_InternalBuildRules(rules)
    }
}

internal fun equals_InternalBuildRules(rules1: InternalBuildRules, rules2: InternalBuildRules):
        Boolean {
    if (rules1.size != rules2.size) {
        return false
    }

    for (entry in rules1.entries) {
        val value = rules2[entry.key]
        if (value == null || !equals_BuildTargetSet(value, entry.value)) {
            return false
        }
    }

    return true
}

internal fun hashCode_InternalBuildRules(rules: InternalBuildRules): Int {
    var result = 17
    for (entry in rules.entries) {
        result += 31 * entry.key.hashCode() + hashCode_BuildTargetSet(entry.value)
    }
    return result
}

internal fun equals_BuildTargetSet(set1: BuildTargetSet, set2: BuildTargetSet): Boolean {
    return set1.contentEquals(set2)
}

internal fun hashCode_BuildTargetSet(set: BuildTargetSet): Int {
    return set.contentHashCode()
}
