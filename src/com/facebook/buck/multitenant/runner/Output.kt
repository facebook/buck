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

package com.facebook.buck.multitenant.runner

import java.io.PrintStream

internal sealed class Output {
    abstract fun print(data: List<String>)
    class Stdout : Output() {
        override fun print(data: List<String>) {
            writeToStream(data, System.out)
        }
    }

    class Stderr : Output() {
        override fun print(data: List<String>) {
            writeToStream(data, System.err)
        }
    }

    class File(val path: String) : Output() {
        override fun print(data: List<String>) {
            PrintStream(path)
                .use { writeToStream(data, it) }
        }
    }
}

private fun writeToStream(data: List<String>, stream: PrintStream) = data.map { stream.println(it) }
