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

import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.multitenant.importer.parseOrdinaryBuildTarget
import com.facebook.buck.multitenant.importer.populateIndexFromStream
import com.google.common.collect.ImmutableSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexTest {

    @Test
    fun getTargetsAndDeps() {
        val bt = ::parseOrdinaryBuildTarget
        val index = Index(bt)

        val commits = populateIndexFromStream(index, IndexTest::class.java.getResourceAsStream("index_test_targets_and_deps.json"))

        val commit1 = commits[0]
        val commit2 = commits[1]
        val commit3 = commits[2]
        val commit4 = commits[3]
        val commit5 = commits[4]

        assertEquals(
                setOf(bt("//java/com/facebook/buck/base:base")),
                index.getTargets(commit1).toSet())
        assertEquals(
                setOf(
                        bt("//java/com/facebook/buck/base:base"),
                        bt("//java/com/facebook/buck/model:model")),
                index.getTargets(commit2).toSet())
        assertEquals(
                setOf(
                        bt("//java/com/facebook/buck/base:base"),
                        bt("//java/com/facebook/buck/model:model"),
                        bt("//java/com/facebook/buck/util:util")),
                index.getTargets(commit3).toSet())
        assertEquals(
                setOf(
                        bt("//java/com/facebook/buck/base:base"),
                        bt("//java/com/facebook/buck/model:model"),
                        bt("//java/com/facebook/buck/util:util")),
                index.getTargets(commit4).toSet())
        assertEquals(
                setOf(
                        bt("//java/com/facebook/buck/base:base"),
                        bt("//java/com/facebook/buck/util:util")),
                index.getTargets(commit5).toSet())

        index.acquireReadLock().use {
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base")
                    ),
                    index.getTransitiveDeps(it, commit2, bt("//java/com/facebook/buck/model:model"))
            )
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base"),
                            bt("//java/com/facebook/buck/util:util")
                    ),
                    index.getTransitiveDeps(it, commit3, bt("//java/com/facebook/buck/model:model"))
            )

            val commit1baseFwdDeps = ImmutableSet.Builder<UnconfiguredBuildTarget>()
            index.getFwdDeps(it, commit1, listOf(bt("//java/com/facebook/buck/base:base")), commit1baseFwdDeps)
            assertEquals(commit1baseFwdDeps.build(), setOf<UnconfiguredBuildTarget>())

            val commit2modelFwdDeps = ImmutableSet.Builder<UnconfiguredBuildTarget>()
            index.getFwdDeps(it, commit2, listOf(bt("//java/com/facebook/buck/model:model")), commit2modelFwdDeps)
            assertEquals(commit2modelFwdDeps.build(), setOf(bt("//java/com/facebook/buck/base:base")))

            val commit3modelFwdDeps = ImmutableSet.Builder<UnconfiguredBuildTarget>()
            index.getFwdDeps(it, commit3, listOf(bt("//java/com/facebook/buck/model:model")), commit3modelFwdDeps)
            assertEquals(commit3modelFwdDeps.build(), setOf(bt("//java/com/facebook/buck/base:base"), bt("//java/com/facebook/buck/util:util")))

            val commit3utilFwdDeps = ImmutableSet.Builder<UnconfiguredBuildTarget>()
            index.getFwdDeps(it, commit3, listOf(bt("//java/com/facebook/buck/util:util")), commit3utilFwdDeps)
            assertEquals(commit3utilFwdDeps.build(), setOf(bt("//java/com/facebook/buck/base:base")))
        }
    }

    @Test
    fun getTargetNodes() {
        val bt = ::parseOrdinaryBuildTarget
        val index = Index(bt)

        val commits = populateIndexFromStream(index, IndexTest::class.java.getResourceAsStream("index_test_targets_and_deps.json"))
        val commit5 = commits[4]

        val targetNodes = index.getTargetNodes(commit5, listOf(
                bt("//java/com/facebook/buck/base:base"),
                bt("//java/com/facebook/buck/model:model"),
                bt("//java/com/facebook/buck/util:util")
        ))
        assertEquals(targetNodes[0]!!.targetNode.ruleType.name, "java_library")
        assertNull("model was deleted at commit 5", targetNodes[1])
        assertEquals(targetNodes[2]!!.deps, setOf(bt("//java/com/facebook/buck/base:base")))

        assertEquals(targetNodes[0], index.getTargetNode(commit5, bt("//java/com/facebook/buck/base:base")))
        assertEquals(targetNodes[1], null)
    }
}
