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

/** oldRules and newRules must come from the same build package. */
internal fun diffRules(oldRules: Set<InternalRawBuildRule>, newRules: Set<InternalRawBuildRule>): List<RuleDelta> {
    val deltas: MutableList<RuleDelta> = mutableListOf()
    val smallMap: MutableMap<String, InternalRawBuildRule>
    val largeMap: Map<String, InternalRawBuildRule>
    val smallMapHasTheOldRules = oldRules.size < newRules.size
    if (smallMapHasTheOldRules) {
        smallMap = oldRules.associate { toPair(it) } .toMutableMap()
        largeMap = newRules.associate { toPair(it) } .toMap()
    } else {
        smallMap = newRules.associate { toPair(it) } .toMutableMap()
        largeMap = oldRules.associate { toPair(it) } .toMap()
    }

    for (entry in largeMap.entries) {
        val rule = smallMap.remove(entry.key)
        if (rule == null) {
            // Entry exists in large map but not small map.
            if (smallMapHasTheOldRules) {
                deltas.add(RuleDelta.Updated(entry.value))
            } else {
                deltas.add(RuleDelta.Removed(entry.value.targetNode.buildTarget))
            }
        } else if (rule != entry.value) {
            // Entry exists in both maps, but it has been modified.
            if (smallMapHasTheOldRules) {
                deltas.add(RuleDelta.Updated(entry.value))
            } else {
                deltas.add(RuleDelta.Updated(rule))
            }
        }
    }

    // Remaining entries are ones that are unique to small map.
    for (rule in smallMap.values) {
        if (smallMapHasTheOldRules) {
            deltas.add(RuleDelta.Removed(rule.targetNode.buildTarget))
        } else {
            deltas.add(RuleDelta.Updated(rule))
        }
    }

    return deltas
}

private fun toPair(rule: InternalRawBuildRule) : Pair<String, InternalRawBuildRule> {
    return Pair(rule.targetNode.buildTarget.name, rule)
}
