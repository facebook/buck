/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.multitenant.service

import org.junit.Assert.assertEquals
import org.junit.Test

class FsChangesReadAheadIteratorTest {
    @Test fun canIterateOneElement() {
        FsChangesReadAheadIterator(listOf("aaa").iterator(), { commits ->
            commits.map {
                FsChanges(commit = it)
            }
        }).asSequence().toList().let {result ->
            assertEquals(1, result.size)
            assertEquals("aaa", result.first().commit)
        }
    }

    @Test fun canIterateTwoElements() {
        FsChangesReadAheadIterator(listOf("aaa", "bbb").iterator(), { commits ->
            commits.map {
                FsChanges(commit = it)
            }
        }).asSequence().toList().let { result ->
            assertEquals(2, result.size)
            assertEquals("aaa", result[0].commit)
            assertEquals("bbb", result[1].commit)
        }
    }

    @Test fun canIterateTwoChunks() {
        FsChangesReadAheadIterator(listOf("1", "2", "3").iterator(), { commits ->
            commits.map {
                FsChanges(commit = it)
            }
        }, bufferSize = 2, readAheadSize = 1).asSequence().toList().let { result ->
            assertEquals(3, result.size)
            assertEquals("1", result[0].commit)
            assertEquals("2", result[1].commit)
            assertEquals("3", result[2].commit)
        }
    }

    @Test fun canIterateWhenChunkSizeIsTheSameAsReadAhead() {
        FsChangesReadAheadIterator(listOf("1", "2", "3").iterator(), { commits ->
            commits.map {
                FsChanges(commit = it)
            }
        }, bufferSize = 1, readAheadSize = 1).asSequence().toList().let { result ->
            assertEquals(3, result.size)
            assertEquals("1", result[0].commit)
            assertEquals("2", result[1].commit)
            assertEquals("3", result[2].commit)
        }
    }
}
