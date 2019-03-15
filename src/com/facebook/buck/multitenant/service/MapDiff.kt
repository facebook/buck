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

import java.nio.file.Path

internal fun diffRules(oldRules: InternalBuildRules, newRules: InternalBuildRules,
                       buildFileDirectory: Path): List<RuleDelta> {
    val deltas: MutableList<RuleDelta> = mutableListOf()
    val smallMap: MutableMap<String, BuildTargetSet>
    val largeMap: InternalBuildRules
    val smallMapHasTheOldRules = oldRules.size < newRules.size
    if (smallMapHasTheOldRules) {
        smallMap = oldRules.toMutableMap()
        largeMap = newRules
    } else {
        smallMap = newRules.toMutableMap()
        largeMap = oldRules
    }

    for (entry in largeMap.entries) {
        val value = smallMap.remove(entry.key)
        if (value == null) {
            // Entry exists in large map but not small map.
            if (smallMapHasTheOldRules) {
                deltas.add(RuleDelta.Updated(buildFileDirectory, entry.key, entry.value))
            } else {
                deltas.add(RuleDelta.Removed(buildFileDirectory, entry.key))
            }
        } else if (!value.contentEquals(entry.value)) {
            // Entry exists in both maps, but it has been modified.
            if (smallMapHasTheOldRules) {
                deltas.add(RuleDelta.Updated(buildFileDirectory, entry.key, entry.value))
            } else {
                deltas.add(RuleDelta.Updated(buildFileDirectory, entry.key, value))
            }
        }
    }

    // Remaining entries are ones that are unique to small map.
    for (entry in smallMap.entries) {
        if (smallMapHasTheOldRules) {
            deltas.add(RuleDelta.Removed(buildFileDirectory, entry.key))
        } else {
            deltas.add(RuleDelta.Updated(buildFileDirectory, entry.key, entry.value))
        }
    }

    return deltas
}
