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

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Iterator object which accepts source control revisions (as an iterator as well) and produce
 * an iterable sequence of file changes related to each of those revisions. It uses the provided
 * commit retriever to prefetch file changes information for commits.
 *
 * @param commitIterator An iterator over commits for which file changes information is to be
 * retrieved
 * @param changeLoader An implementation that actually get commit information from source control
 * @param readAheadSize When less than this number of elements is remaining in a buffer, query
 * source control for next chunk of data
 * @param bufferSize The number of elements to read ahead, i.e. the size of the chunk
 */
class FsChangesReadAheadIterator(
    private val commitIterator: Iterator<Commit>,
    private val changeLoader: (List<Commit>) -> List<FsChanges>,
    private val bufferSize: Int = 500,
    private val readAheadSize: Int = 100
) : Iterator<FsChanges> {

    init {
        check(bufferSize > 0) {
            "bufferSize should be positive"
        }

        check(readAheadSize > 0) {
            "readAheadSize should be positive"
        }

        check(readAheadSize <= bufferSize) {
            "readAheadSize should not be greater than bufferSize"
        }
    }

    private var buffer = listOf<FsChanges>()
    private var pos = 0
    private var job: Deferred<List<FsChanges>>? = tryScheduleNextChunkJob()

    override fun hasNext(): Boolean {
        return pos < buffer.size || job != null
    }

    override fun next(): FsChanges {
        return if (pos < buffer.size) {
            if (pos == buffer.size - readAheadSize) {
                job = tryScheduleNextChunkJob()
            }
            pos++
            buffer[pos - 1]
        } else {
            // as current buffer depletes, check for the previously scheduled job to get next
            // chunk of data to complete, waiting if necessary. If there is no such a job it means
            // no more elements in the iterator so we throw [NoSuchElementException] by contract
            val nextBuffer = runBlocking {
                job?.await() } ?: throw NoSuchElementException("No more data")

            check(nextBuffer.isNotEmpty()) {
                // This should never happen. For known commits passed in [commitIterator], loader
                // should always return appropriate metadata.
                "No data returned by change loader"
            }

            // replace buffer with the data fetched with next chunk and restart the process
            buffer = nextBuffer
            job = null
            pos = 0
            next()
        }
    }

    /**
     * Schedule a new job to get next chunk of file change info. If there are no more
     * commits in the original commit iterator, this returns null
     */
    private fun tryScheduleNextChunkJob(): Deferred<List<FsChanges>>? {
        // We have to iterate commitIterator on main thread and make a copy of the revision list to
        // avoid racing with background thread. Moving iteration to background thread is more
        // complex and is only beneficial if commitIterator is slow, but we assume it is fast
        val nextCommits = commitIterator
            .asSequence()
            .take(bufferSize)
            .toList()

        return if (nextCommits.isEmpty()) {
            null
        } else {
            runBlocking {
                // Execute on common thread pool
                async(Dispatchers.Default) {
                    changeLoader(nextCommits)
                }
            }
        }
    }
}
