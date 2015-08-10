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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

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

  public JavacOptions getDefaultJavacOptions() {
    Optional<String> sourceLevel = delegate.getValue("java", "source_level");
    Optional<String> targetLevel = delegate.getValue("java", "target_level");
    ImmutableList<String> extraArguments = delegate.getListWithoutComments(
        "java",
        "extra_arguments");

    ImmutableMap<String, String> allEntries = delegate.getEntriesForSection("java");
    ImmutableMap.Builder<String, String> bootclasspaths = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : allEntries.entrySet()) {
      if (entry.getKey().startsWith("bootclasspath-")) {
        bootclasspaths.put(entry.getKey().substring("bootclasspath-".length()), entry.getValue());
      }
    }

    return JavacOptions.builderForUseInJavaBuckConfig()
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

  Optional<SourcePath> getJavacJarPath() {
    return delegate.getSourcePath("tools", "javac_jar");
  }

  public boolean getSkipCheckingMissingDeps() {
    return delegate.getBooleanValue("java", "skip_checking_missing_deps", false);
  }
}
