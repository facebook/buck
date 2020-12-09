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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfoFactory;
import com.facebook.buck.util.Escaper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Interface for a resolved javac tool. */
public interface ResolvedJavac {

  /** An escaper for arguments written to @argfiles. */
  Function<String, String> ARGFILES_ESCAPER = Escaper.javacEscaper();

  /** Prepares an invocation of the compiler with the given parameters. */
  ResolvedJavac.Invocation newBuildInvocation(
      JavacExecutionContext context,
      BuildTargetValue invokingRule,
      CompilerOutputPathsValue compilerOutputPathsValue,
      ImmutableList<String> options,
      ImmutableList<JavacPluginJsr199Fields> annotationProcessors,
      ImmutableList<JavacPluginJsr199Fields> javacPlugins,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      RelPath pathToSrcsList,
      RelPath workingDirectory,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      @Nullable SourceOnlyAbiRuleInfoFactory ruleInfoFactory);

  String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      RelPath pathToSrcsList);

  /** Returns a short name of the tool */
  String getShortName();

  /** Enum that specify a type of java compiler. */
  enum Source {
    /** Shell out to the javac in the JDK */
    EXTERNAL,
    /** Run javac in-process, loading it from a jar specified in .buckconfig. */
    JAR,
    /** Run javac in-process, loading it from the JRE in which Buck is running. */
    JDK,
  }

  /** Interface that defines invocation object created during java compilation. */
  interface Invocation extends AutoCloseable {

    /**
     * Produces a source-only ABI jar. {@link #buildClasses} may not be called on an invocation on
     * which this has been called.
     */
    int buildSourceOnlyAbiJar() throws InterruptedException;

    /** Produces a source ABI jar. Must be called before {@link #buildClasses} */
    int buildSourceAbiJar() throws InterruptedException;

    int buildClasses() throws InterruptedException;

    @Override
    void close();
  }
}
