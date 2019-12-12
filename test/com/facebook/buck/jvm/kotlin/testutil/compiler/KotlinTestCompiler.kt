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

package com.facebook.buck.jvm.kotlin.testutil.compiler


import com.facebook.buck.jvm.java.testutil.compiler.Classes
import com.facebook.buck.jvm.java.testutil.compiler.ClassesImpl
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.Assert.fail
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder

/**
 * A [org.junit.Rule] for working with kotlinc in tests.
 *
 * Add it as a public field like this:
 * <pre>
 * &#64;Rule
 * public KotlinTestCompiler testCompiler = new KotlinTestCompiler();
</pre> *
 */
class KotlinTestCompiler : ExternalResource(), AutoCloseable {

    private val kotlinCompiler = K2JVMCompiler()
    private val inputFolder = TemporaryFolder()
    private val outputFolder = TemporaryFolder()
    private val abiOutputFolder = TemporaryFolder()

    private val sourceFiles: MutableList<File> = mutableListOf()
    private val classpath: MutableSet<String> = mutableSetOf()
    val classes: Classes = ClassesImpl(outputFolder)
    val abiClasses: Classes = ClassesImpl(abiOutputFolder)

    @Throws(IOException::class) fun addSourceFileContents(fileName: String, vararg lines: String) {
        val sourceFilePath = inputFolder.root.toPath().resolve(fileName)

        sourceFilePath.toFile().parentFile.mkdirs()
        Files.write(sourceFilePath, lines.asList(), StandardCharsets.UTF_8)

        sourceFiles.add(sourceFilePath.toFile())
    }

    fun addClasspath(paths: Collection<Path>) {
        classpath.addAll(paths.map { it.toString() })
    }

    fun compile() {
        val collector =
            PrintingMessageCollector(System.err, MessageRenderer.PLAIN_RELATIVE_PATHS, true)
        val k2JVMCompilerArguments = K2JVMCompilerArguments().also {
            it.noStdlib = true
            it.noReflect = true
            it.freeArgs = sourceFiles.map(File::getAbsolutePath).distinct()
            it.destination = outputFolder.root.toString()

            it.classpath = classpath.joinToString(File.pathSeparator)
            it.classpath += File.pathSeparator + System.getProperty("java.class.path")

            it.pluginClasspaths = arrayOf("third-party/java/kotlin/jvm-abi-gen.jar")
            it.pluginOptions =
                arrayOf("plugin:org.jetbrains.kotlin.jvm.abi:outputDir=${abiOutputFolder.root}")
        }

        kotlinCompiler.exec(collector, Services.EMPTY, k2JVMCompilerArguments)

        if (collector.hasErrors()) {
            fail("Kotlin compilation failed with errors, see stderr for details")
        }
    }

    fun init() {
        try {
            before()
        } catch (ioe: IOException) {
            throw AssertionError(ioe)
        }
    }

    @Throws(IOException::class) override fun before() {
        inputFolder.create()
        outputFolder.create()
        abiOutputFolder.create()
    }

    override fun after() {
        abiOutputFolder.delete()
        outputFolder.delete()
        inputFolder.delete()
    }

    override fun close() {
        after()
    }
}
