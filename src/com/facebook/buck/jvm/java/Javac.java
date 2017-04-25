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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

public interface Javac extends RuleKeyAppendable, Tool {

  /** An escaper for arguments written to @argfiles. */
  Function<String, String> ARGFILES_ESCAPER = Escaper.javacEscaper();

  String SRC_ZIP = ".src.zip";
  String SRC_JAR = "-sources.jar";

  JavacVersion getVersion();

  int buildWithClasspath(
      JavacExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableList<ResolvedJavacPluginProperties> annotationProcessors,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      CompilationMode compilationMode)
      throws InterruptedException;

  String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList);

  String getShortName();

  enum Location {
    /** Perform compilation inside main process. */
    IN_PROCESS,
    /** Delegate compilation into separate process. */
    OUT_OF_PROCESS,
  }

  enum Source {
    /** Shell out to the javac in the JDK */
    EXTERNAL,
    /** Run javac in-process, loading it from a jar specified in .buckconfig. */
    JAR,
    /** Run javac in-process, loading it from the JRE in which Buck is running. */
    JDK,
  }

  enum CompilationMode {
    /** Normal compilation. Generates full jars. */
    FULL,
    /**
     * Normal compilation with additional checking of type and constant references for compatibility
     * with generating ABI jars from source without dependencies. This mode issues warnings when
     * incompatibilities are detected and is thus intended for use during migration.
     */
    FULL_CHECKING_REFERENCES,
    /**
     * Normal compilation with additional checking of type and constant references for compatibility
     * with generating ABI jars from source wtihout dependencies. This mode issues errors when
     * incompatibilities are detected; it is the backstop that ensures the build will fail if an
     * incorrect ABI jar is generated.
     */
    FULL_ENFORCING_REFERENCES,
    /** Compile ABIs only. Generates an ABI jar. */
    ABI,
  }
}
