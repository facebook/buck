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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.types.Either;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** A java-specific "view" of BuckConfig. */
public class JavaBuckConfig implements ConfigView<BuckConfig> {

  public static final String SECTION = "java";
  public static final String PROPERTY_COMPILE_AGAINST_ABIS = "compile_against_abis";
  private static final JavaOptions DEFAULT_JAVA_OPTIONS =
      JavaOptions.of(new CommandTool.Builder().addArg("java").build());

  private final BuckConfig delegate;
  private final Supplier<JavacSpec> javacSpecSupplier;

  // Interface for reflection-based ConfigView to instantiate this class.
  public static JavaBuckConfig of(BuckConfig delegate) {
    return new JavaBuckConfig(delegate);
  }

  private JavaBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
    this.javacSpecSupplier =
        MoreSuppliers.memoize(
            () ->
                JavacSpec.builder()
                    .setJavacPath(
                        getJavacPath().isPresent()
                            ? Optional.of(Either.ofLeft(getJavacPath().get()))
                            : Optional.empty())
                    .setJavacJarPath(delegate.getSourcePath("tools", "javac_jar"))
                    .setJavacLocation(
                        delegate
                            .getEnum(SECTION, "location", Javac.Location.class)
                            .orElse(Javac.Location.IN_PROCESS))
                    .setCompilerClassName(delegate.getValue("tools", "compiler_class_name"))
                    .build());
  }

  @Override
  public BuckConfig getDelegate() {
    return delegate;
  }

  public JavaOptions getDefaultJavaOptions() {
    return getToolForExecutable("java").map(JavaOptions::of).orElse(DEFAULT_JAVA_OPTIONS);
  }

  public JavaOptions getDefaultJavaOptionsForTests() {
    return getToolForExecutable("java_for_tests")
        .map(JavaOptions::of)
        .orElseGet(this::getDefaultJavaOptions);
  }

  public JavacOptions getDefaultJavacOptions() {
    JavacOptions.Builder builder = JavacOptions.builderForUseInJavaBuckConfig();

    Optional<String> sourceLevel = delegate.getValue(SECTION, "source_level");
    if (sourceLevel.isPresent()) {
      builder.setSourceLevel(sourceLevel.get());
    }

    Optional<String> targetLevel = delegate.getValue(SECTION, "target_level");
    if (targetLevel.isPresent()) {
      builder.setTargetLevel(targetLevel.get());
    }

    ImmutableList<String> extraArguments =
        delegate.getListWithoutComments(SECTION, "extra_arguments");

    ImmutableList<String> safeAnnotationProcessors =
        delegate.getListWithoutComments(SECTION, "safe_annotation_processors");

    builder.setTrackClassUsage(trackClassUsage());

    Optional<AbstractJavacOptions.SpoolMode> spoolMode =
        delegate.getEnum(SECTION, "jar_spool_mode", AbstractJavacOptions.SpoolMode.class);
    if (spoolMode.isPresent()) {
      builder.setSpoolMode(spoolMode.get());
    }

    ImmutableMap<String, String> allEntries = delegate.getEntriesForSection(SECTION);
    ImmutableMap.Builder<String, String> bootclasspaths = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : allEntries.entrySet()) {
      if (entry.getKey().startsWith("bootclasspath-")) {
        bootclasspaths.put(entry.getKey().substring("bootclasspath-".length()), entry.getValue());
      }
    }

    return builder
        .putAllSourceToBootclasspath(bootclasspaths.build())
        .addAllExtraArguments(extraArguments)
        .setSafeAnnotationProcessors(safeAnnotationProcessors)
        .build();
  }

  public AbiGenerationMode getAbiGenerationMode() {
    return delegate
        .getEnum(SECTION, "abi_generation_mode", AbiGenerationMode.class)
        .orElse(AbiGenerationMode.CLASS);
  }

  public ImmutableSet<String> getSrcRoots() {
    return ImmutableSet.copyOf(delegate.getListWithoutComments(SECTION, "src_roots"));
  }

  public DefaultJavaPackageFinder createDefaultJavaPackageFinder() {
    return DefaultJavaPackageFinder.createDefaultJavaPackageFinder(getSrcRoots());
  }

  public boolean trackClassUsage() {
    // This is just to make it possible to turn off dep-based rulekeys in case anything goes wrong
    // and can be removed when we're sure class usage tracking and dep-based keys for Java
    // work fine.
    Optional<Boolean> trackClassUsage = delegate.getBoolean(SECTION, "track_class_usage");
    if (trackClassUsage.isPresent() && !trackClassUsage.get()) {
      return false;
    }

    final Javac.Source javacSource = getJavacSpec().getJavacSource();
    return (javacSource == Javac.Source.JAR || javacSource == Javac.Source.JDK);
  }

  public JavacSpec getJavacSpec() {
    return javacSpecSupplier.get();
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

  private Optional<Tool> getToolForExecutable(String executableName) {
    return delegate
        // Make sure to pass `false` for `isCellRootRelative` so that we get a relative path back,
        // instead of an absolute one.  Otherwise, we can't preserve the original value.
        .getPath("tools", executableName, false)
        .map(
            path -> {
              if (!Files.isExecutable(
                  delegate.resolvePathThatMayBeOutsideTheProjectFilesystem(path))) {
                throw new HumanReadableException(executableName + " is not executable: " + path);
              }

              // Build the tool object.  For absolute paths, just add the raw string and avoid
              // hashing the contents, as this would require all users to have identical system
              // binaries, when what we probably only care about is the version.
              return new CommandTool.Builder()
                  .addArg(
                      path.isAbsolute()
                          ? StringArg.of(path.toString())
                          : SourcePathArg.of(
                              Preconditions.checkNotNull(delegate.getPathSourcePath(path))))
                  .build();
            });
  }

  public boolean shouldCacheBinaries() {
    return delegate.getBooleanValue(SECTION, "cache_binaries", true);
  }

  public Optional<Integer> getDxThreadCount() {
    return delegate.getInteger(SECTION, "dx_threads");
  }

  /**
   * Controls a special verification mode that generates ABIs both from source and from class files
   * and diffs them. This is a test hook for use during development of the source ABI feature. This
   * only has meaning when {@link #getAbiGenerationMode()} is one of the source modes.
   */
  public SourceAbiVerificationMode getSourceAbiVerificationMode() {
    if (!getAbiGenerationMode().isSourceAbi()) {
      return SourceAbiVerificationMode.OFF;
    }

    return delegate
        .getEnum(SECTION, "source_abi_verification_mode", SourceAbiVerificationMode.class)
        .orElse(SourceAbiVerificationMode.OFF);
  }

  public boolean shouldCompileAgainstAbis() {
    return delegate.getBooleanValue(SECTION, PROPERTY_COMPILE_AGAINST_ABIS, false);
  }

  public Optional<String> getDefaultCxxPlatform() {
    return delegate.getValue(SECTION, "default_cxx_platform");
  }

  public enum SourceAbiVerificationMode {
    /** Don't verify ABI jars. */
    OFF,
    /** Generate ABI jars from classes and from source. Log any differences. */
    LOG,
    /** Generate ABI jars from classes and from source. Fail on differences. */
    FAIL,
  }
}
