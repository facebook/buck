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

import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.math.ln
import kotlin.math.pow

private const val KB_IN_BYTES = 1024

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

    /**
     * @return human readable representation of index data size
     *
     *[Copied_from](https://programming.guide/java/formatting-byte-size-to-human-readable-format.html)
     */
    fun getHumanReadableSize(): String {
        val bytes = getSize()
        if (bytes < KB_IN_BYTES) return "$bytes B"
        val unit = KB_IN_BYTES.toDouble()
        val exp = (ln(bytes.toDouble()) / ln(unit)).toInt()
        val pre = "KMGTPE"[exp - 1].toString()
        return String.format("%.1f %sB", bytes / unit.pow(exp.toDouble()), pre)
    }

    companion object {
        fun from(uri: URI): InputSource {
            return when {
                uri.scheme == "http" || uri.scheme == "https" -> Http(
                    uri.toURL())
                uri.scheme == "file" || uri.scheme.isNullOrEmpty() -> File(
                    uri.path)
                else -> throw IllegalArgumentException(
                    "schema ${uri.scheme} is not supported")
            }
        }
    }

    /**
     * Reads index data from a file on the machine
     */
    data class File(val path: String) : InputSource() {
        private val javaFile: java.io.File = java.io.File(path)
        private var stream: InputStream? = null

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
    data class Http(val url: URL, val readTimeout: Int? = null, val connectTimeout: Int? = null) : InputSource() {
        private val connection: HttpURLConnection
        private var connected = false

        init {
            @Suppress("UnsafeCast")
            connection = url.openConnection() as HttpURLConnection
            connectTimeout?.let { connection.connectTimeout = it }
            readTimeout?.let { connection.readTimeout = it }
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
