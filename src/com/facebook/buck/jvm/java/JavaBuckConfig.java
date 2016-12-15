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

package com.facebook.buck.jvm.java;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * A java-specific "view" of BuckConfig.
 */
public class JavaBuckConfig implements ConfigView<BuckConfig> {
  private final BuckConfig delegate;

  // Interface for reflection-based ConfigView to instantiate this class.
  public static JavaBuckConfig of(BuckConfig delegate) {
    return new JavaBuckConfig(delegate);
  }

  private JavaBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  @Override
  public BuckConfig getDelegate() {
    return delegate;
  }

  public JavaOptions getDefaultJavaOptions() {
    return JavaOptions
        .builder()
        .setJavaPath(getPathToExecutable("java"))
        .build();
  }

  public JavaOptions getDefaultJavaOptionsForTests() {
    Optional<Path> javaTestPath = getPathToExecutable("java_for_tests");
    if (javaTestPath.isPresent()) {
      return JavaOptions
          .builder()
          .setJavaPath(javaTestPath)
          .build();
    }
    return getDefaultJavaOptions();
  }

  public JavacOptions getDefaultJavacOptions() {
    JavacOptions.Builder builder = JavacOptions.builderForUseInJavaBuckConfig();

    Optional<String> sourceLevel = delegate.getValue("java", "source_level");
    if (sourceLevel.isPresent()) {
      builder.setSourceLevel(sourceLevel.get());
    }

    Optional<String> targetLevel = delegate.getValue("java", "target_level");
    if (targetLevel.isPresent()) {
      builder.setTargetLevel(targetLevel.get());
    }

    Optional<JavacOptions.JavacLocation> location = delegate.getEnum(
        "java",
        "location",
        JavacOptions.JavacLocation.class);
    if (location.isPresent()) {
      builder.setJavacLocation(location.get());
    }

    ImmutableList<String> extraArguments = delegate.getListWithoutComments(
        "java",
        "extra_arguments");

    ImmutableList<String> safeAnnotationProcessors = delegate.getListWithoutComments(
        "java",
        "safe_annotation_processors");

    AbstractJavacOptions.SpoolMode spoolMode = delegate
        .getEnum("java", "jar_spool_mode", AbstractJavacOptions.SpoolMode.class).orElse(
            AbstractJavacOptions.SpoolMode.INTERMEDIATE_TO_DISK);

    // This is just to make it possible to turn off dep-based rulekeys in case anything goes wrong
    // and can be removed when we're sure class usage tracking and dep-based keys for Java
    // work fine.
    Optional<Boolean> trackClassUsage = delegate.getBoolean("java", "track_class_usage");
    if (trackClassUsage.isPresent()) {
      builder.setTrackClassUsageNotDisabled(trackClassUsage.get());
    }

    ImmutableMap<String, String> allEntries = delegate.getEntriesForSection("java");
    ImmutableMap.Builder<String, String> bootclasspaths = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : allEntries.entrySet()) {
      if (entry.getKey().startsWith("bootclasspath-")) {
        bootclasspaths.put(entry.getKey().substring("bootclasspath-".length()), entry.getValue());
      }
    }

    return builder
        .setJavacPath(
            getJavacPath().map(Either::ofLeft))
        .setJavacJarPath(getJavacJarPath())
        .setCompilerClassName(getCompilerClassName())
        .setSpoolMode(spoolMode)
        .putAllSourceToBootclasspath(bootclasspaths.build())
        .addAllExtraArguments(extraArguments)
        .setSafeAnnotationProcessors(safeAnnotationProcessors)
        .build();
  }

  public ImmutableSet<String> getSrcRoots() {
    return ImmutableSet.copyOf(delegate.getListWithoutComments("java", "src_roots"));
  }

  public DefaultJavaPackageFinder createDefaultJavaPackageFinder() {
    return DefaultJavaPackageFinder.createDefaultJavaPackageFinder(getSrcRoots());
  }

  @VisibleForTesting
  Optional<Path> getJavacPath() {
    return getPathToExecutable("javac");
  }

  private Optional<Path> getPathToExecutable(String executableName) {
    Optional<Path> path = delegate.getPath("tools", executableName);
    if (path.isPresent()) {
      File file = path.get().toFile();
      if (!file.canExecute()) {
        throw new HumanReadableException(executableName + " is not executable: " + file.getPath());
      }
      return Optional.of(file.toPath());
    }
    return Optional.empty();
  }

  Optional<SourcePath> getJavacJarPath() {
    return delegate.getSourcePath("tools", "javac_jar");
  }

  Optional<String> getCompilerClassName() {
    return delegate.getValue("tools", "compiler_class_name");
  }

  public boolean getSkipCheckingMissingDeps() {
    return delegate.getBooleanValue("java", "skip_checking_missing_deps", true);
  }

  public Optional<Integer> getDxThreadCount() {
    return delegate.getInteger("java", "dx_threads");
  }
}
