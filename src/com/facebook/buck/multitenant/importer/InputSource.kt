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

package com.facebook.buck.multitenant.importer

import java.io.Closeable
import java.io.InputStream
import java.lang.IllegalArgumentException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Abstracts away physical source of index data, like file or network.
 */
sealed class InputSource : Closeable {

    /**
     * @return Stream to read index data from. Client is not required to close the stream, but
     * client is required to call [close] on current instance of [InputSource]
     */
    abstract fun getInputStream(): InputStream

    /**
     * Size of index data in bytes, if available, otherwise -1
     */
    abstract fun getSize(): Long

    companion object {
        fun from(uri: URI): InputSource {
            return when {
                uri.scheme == "http" || uri.scheme == "https" -> Http(uri.toURL())
                uri.scheme == "file" || uri.scheme.isNullOrEmpty() -> File(uri.path)
                else -> throw IllegalArgumentException(
                    "schema ${uri.scheme} is not supported")
            }
        }
    }

    /**
     * Reads index data from a file on the machine
     */
    data class File(val path: String) : InputSource() {
        private val javaFile: java.io.File
        private var stream: InputStream? = null

        init {
            javaFile = java.io.File(path)
        }

        private fun ensureOpened() {
            if (stream != null) {
                return
            }
            stream = javaFile.inputStream()
        }

        override fun getInputStream(): InputStream {
            ensureOpened()
            return checkNotNull(stream)
        }

        override fun getSize() = javaFile.length()

        override fun close() {
            stream?.close()
        }
    }

    /**
     * Reads index data from HTTP url
     */
    data class Http(val url: URL) : InputSource() {
        private val connection: HttpURLConnection
        private var connected = false

        init {
            @Suppress("UnsafeCast")
            connection = url.openConnection() as HttpURLConnection
        }

        private fun ensureConnected() {
            if (connected) {
                return
            }
            connection.connect()
            connected = true
        }

        override fun getInputStream(): InputStream {
            ensureConnected()
            return connection.inputStream
        }

        override fun getSize(): Long {
            ensureConnected()
            return connection.contentLengthLong
        }

        override fun close() {
            if (connected) {
                connection.disconnect()
            }
        }
    }
}
