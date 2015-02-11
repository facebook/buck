/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A java-specific "view" of BuckConfig.
 */
public class JavaBuckConfig {
  // Default combined source and target level.
  public static final String TARGETED_JAVA_VERSION = "7";
  private final BuckConfig delegate;

  public JavaBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public JavacOptions getDefaultJavacOptions(ProcessExecutor processExecutor) {
    Optional<String> sourceLevel = delegate.getValue("java", "source_level");
    Optional<String> targetLevel = delegate.getValue("java", "target_level");
    Optional<String> extraArgumentsString = delegate.getValue("java", "extra_arguments");

    ImmutableList<String> extraArguments =
        ImmutableList.copyOf(
            Splitter.on(Pattern.compile("[ ,]+"))
            .omitEmptyStrings()
            .split(extraArgumentsString.or("")));

    ImmutableMap<String, String> allEntries = delegate.getEntriesForSection("java");
    ImmutableMap.Builder<String, String> bootclasspaths = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : allEntries.entrySet()) {
      if (entry.getKey().startsWith("bootclasspath-")) {
        bootclasspaths.put(entry.getKey().substring("bootclasspath-".length()), entry.getValue());
      }
    }

    return JavacOptions.builderForUseInJavaBuckConfig()
        .setProcessExecutor(processExecutor)
        .setJavacPath(getJavacPath())
        .setJavacJarPath(getJavacJarPath())
        .setSourceLevel(sourceLevel.or(TARGETED_JAVA_VERSION))
        .setTargetLevel(targetLevel.or(TARGETED_JAVA_VERSION))
        .putAllSourceToBootclasspath(bootclasspaths.build())
        .addAllExtraArguments(extraArguments)
        .build();
  }

  @VisibleForTesting
  Optional<Path> getJavacPath() {
    Optional<String> path = delegate.getValue("tools", "javac");
    if (path.isPresent()) {
      File javac = new File(path.get());
      if (!javac.exists()) {
        throw new HumanReadableException("Javac does not exist: " + javac.getPath());
      }
      if (!javac.canExecute()) {
        throw new HumanReadableException("Javac is not executable: " + javac.getPath());
      }
      return Optional.of(javac.toPath());
    }
    return Optional.absent();
  }

  Optional<Path> getJavacJarPath() {
    Optional<String> path = delegate.getValue("tools", "javac_jar");
    if (path.isPresent()) {
      File javacJar = new File(path.get());
      if (!javacJar.exists()) {
        throw new HumanReadableException(
            "Javac JAR does not exist: " + javacJar.getPath());
      }

      return Optional.of(javacJar.toPath());
    }

    return Optional.absent();
  }
}
