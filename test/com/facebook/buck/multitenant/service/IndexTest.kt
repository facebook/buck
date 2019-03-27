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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class IndexTest {

    @Test
    fun getTargetsAndDeps() {
        val bt = BUILD_TARGET_PARSER
        val index = Index(bt)
        /*
         * //java/com/facebook/buck/base:base has no deps.
         */
        val changes1 = Changes(
                addedBuildPackages = listOf(
                        BuildPackage(Paths.get("java/com/facebook/buck/base"),
                                setOf(
                                        createRawRule("//java/com/facebook/buck/base:base", setOf())
                                ))
                ),
                modifiedBuildPackages = listOf(),
                removedBuildPackages = listOf())
        val commit1 = "608fd7bdf9"
        index.addCommitData(commit1, changes1)

        /*
         * //java/com/facebook/buck/model:model depends on //java/com/facebook/buck/base:base.
         */
        val changes2 = Changes(
                addedBuildPackages = listOf(
                        BuildPackage(Paths.get("java/com/facebook/buck/model"),
                                setOf(
                                        createRawRule("//java/com/facebook/buck/model:model", setOf(
                                                "//java/com/facebook/buck/base:base"
                                        )))
                        )),
                modifiedBuildPackages = listOf(),
                removedBuildPackages = listOf())
        val commit2 = "9efba3bca1"
        index.addCommitData(commit2, changes2)

        /*
         * //java/com/facebook/buck/util:util is introduced and
         * //java/com/facebook/buck/model:model is updated to depend on it.
         */
        val changes3 = Changes(
                addedBuildPackages = listOf(
                        BuildPackage(Paths.get("java/com/facebook/buck/util"),
                                setOf(
                                        createRawRule("//java/com/facebook/buck/util:util", setOf(
                                                "//java/com/facebook/buck/base:base"
                                        ))
                                ))
                ),
                modifiedBuildPackages = listOf(
                        BuildPackage(Paths.get("java/com/facebook/buck/model"),
                                setOf(
                                        createRawRule("//java/com/facebook/buck/model:model", setOf(
                                                "//java/com/facebook/buck/base:base",
                                                "//java/com/facebook/buck/util:util"
                                        ))
                                ))
                ),
                removedBuildPackages = listOf())
        val commit3 = "1b522b5b47"
        index.addCommitData(commit3, changes3)

        /* Nothing changes! */
        val changes4 = Changes(listOf(), listOf(), listOf())
        val commit4 = "270c3e4c42"
        index.addCommitData(commit4, changes4)

        /*
         * //java/com/facebook/buck/model:model is removed.
         */
        val changes5 = Changes(
                addedBuildPackages = listOf(),
                modifiedBuildPackages = listOf(),
                removedBuildPackages = listOf(Paths.get("java/com/facebook/buck/model")))
        val commit5 = "c880d5b5d8"
        index.addCommitData(commit5, changes5)

        index.acquireReadLock().use {
            assertEquals(
                    setOf(bt("//java/com/facebook/buck/base:base")),
                    index.getTargets(it, commit1).toSet())
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base"),
                            bt("//java/com/facebook/buck/model:model")),
                    index.getTargets(it, commit2).toSet())
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base"),
                            bt("//java/com/facebook/buck/model:model"),
                            bt("//java/com/facebook/buck/util:util")),
                    index.getTargets(it, commit3).toSet())
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base"),
                            bt("//java/com/facebook/buck/model:model"),
                            bt("//java/com/facebook/buck/util:util")),
                    index.getTargets(it, commit4).toSet())
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base"),
                            bt("//java/com/facebook/buck/util:util")),
                    index.getTargets(it, commit5).toSet())

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
        }
    }
}
