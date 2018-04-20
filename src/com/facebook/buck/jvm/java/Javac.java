/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.Escaper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Interface for a javac tool. */
public interface Javac extends Tool {
  /** An escaper for arguments written to @argfiles. */
  Function<String, String> ARGFILES_ESCAPER = Escaper.javacEscaper();

  String SRC_ZIP = ".src.zip";
  String SRC_JAR = "-sources.jar";

  /** Prepares an invocation of the compiler with the given parameters. */
  Invocation newBuildInvocation(
      JavacExecutionContext context,
      SourcePathResolver resolver,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableList<JavacPluginJsr199Fields> pluginFields,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      Path workingDirectory,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      @Nullable SourceOnlyAbiRuleInfo ruleInfo);

  String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList);

  String getShortName();

  // TODO(cjhopman): Delete this.
  enum Location {
    /** Perform compilation inside main process. */
    IN_PROCESS,
  }

  enum Source {
    /** Shell out to the javac in the JDK */
    EXTERNAL,
    /** Run javac in-process, loading it from a jar specified in .buckconfig. */
    JAR,
    /** Run javac in-process, loading it from the JRE in which Buck is running. */
    JDK,
  }

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
