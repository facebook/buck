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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.logging.Level;

/** A java-specific "view" of BuckConfig. */
public class JavaBuckConfig implements ConfigView<BuckConfig> {

  public static final String SECTION = "java";
  public static final String PROPERTY_COMPILE_AGAINST_ABIS = "compile_against_abis";
  public static final CommandTool DEFAULT_JAVA_TOOL =
      new CommandTool.Builder().addArg("java").build();
  static final JavaOptions DEFAULT_JAVA_OPTIONS =
      JavaOptions.of(new ConstantToolProvider(DEFAULT_JAVA_TOOL));

  private final BuckConfig delegate;
  private final Function<TargetConfiguration, JavacSpec> javacSpecSupplier;

  // Interface for reflection-based ConfigView to instantiate this class.
  public static JavaBuckConfig of(BuckConfig delegate) {
    return new JavaBuckConfig(delegate);
  }

  private JavaBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
    this.javacSpecSupplier =
        targetConfiguration ->
            JavacSpec.builder()
                .setJavacPath(getJavacPath(targetConfiguration))
                .setJavacJarPath(getJavacJarPath(targetConfiguration))
                .setCompilerClassName(delegate.getValue("tools", "compiler_class_name"))
                .build();
  }

  @Override
  public BuckConfig getDelegate() {
    return delegate;
  }

  public JavaOptions getDefaultJavaOptions() {
    return getToolForExecutable("java")
        .map(ConstantToolProvider::new)
        .map(JavaOptions::of)
        .orElse(DEFAULT_JAVA_OPTIONS);
  }

  public JavaOptions getDefaultJavaOptionsForTests() {
    return getToolForExecutable("java_for_tests")
        .map(ConstantToolProvider::new)
        .map(JavaOptions::of)
        .orElseGet(this::getDefaultJavaOptions);
  }

  public JavacLanguageLevelOptions getJavacLanguageLevelOptions() {
    JavacLanguageLevelOptions.Builder builder = JavacLanguageLevelOptions.builder();
    delegate.getValue(SECTION, "source_level").map(builder::setSourceLevel);
    delegate.getValue(SECTION, "target_level").map(builder::setTargetLevel);
    return builder.build();
  }

  public JavacOptions getDefaultJavacOptions(TargetConfiguration targetConfiguration) {
    JavacOptions.Builder builder = JavacOptions.builderForUseInJavaBuckConfig();

    builder.setLanguageLevelOptions(getJavacLanguageLevelOptions());

    ImmutableList<String> extraArguments =
        delegate.getListWithoutComments(SECTION, "extra_arguments");

    builder.setTrackClassUsage(trackClassUsage(targetConfiguration));
    Optional<Boolean> trackJavacPhaseEvents =
        delegate.getBoolean(SECTION, "track_javac_phase_events");
    if (trackJavacPhaseEvents.isPresent()) {
      builder.setTrackJavacPhaseEvents(trackJavacPhaseEvents.get());
    }

    Optional<AbstractJavacOptions.SpoolMode> spoolMode =
        delegate.getEnum(SECTION, "jar_spool_mode", AbstractJavacOptions.SpoolMode.class);
    if (spoolMode.isPresent()) {
      builder.setSpoolMode(spoolMode.get());
    }

    ImmutableMap<String, String> allEntries = delegate.getEntriesForSection(SECTION);
    ImmutableMap.Builder<String, ImmutableList<PathSourcePath>> bootclasspaths =
        ImmutableMap.builder();
    for (Map.Entry<String, String> entry : allEntries.entrySet()) {
      String key = entry.getKey();
      if (key.startsWith("bootclasspath-")) {
        String[] values = entry.getValue().split(File.pathSeparator);
        ImmutableList.Builder<PathSourcePath> pathsBuilder =
            ImmutableList.builderWithExpectedSize(values.length);
        for (String value : values) {
          Preconditions.checkState(value != null);
          pathsBuilder.add(PathSourcePath.of(delegate.getFilesystem(), Paths.get(value)));
        }

        bootclasspaths.put(key.substring("bootclasspath-".length()), pathsBuilder.build());
      }
    }

    return builder
        .putAllSourceToBootclasspath(bootclasspaths.build())
        .addAllExtraArguments(extraArguments)
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

  public boolean trackClassUsage(TargetConfiguration targetConfiguration) {
    // This is just to make it possible to turn off dep-based rulekeys in case anything goes wrong
    // and can be removed when we're sure class usage tracking and dep-based keys for Java
    // work fine.
    Optional<Boolean> trackClassUsage = delegate.getBoolean(SECTION, "track_class_usage");
    if (trackClassUsage.isPresent() && !trackClassUsage.get()) {
      return false;
    }

    Javac.Source javacSource = getJavacSpec(targetConfiguration).getJavacSource();
    return (javacSource == Javac.Source.JAR || javacSource == Javac.Source.JDK);
  }

  public boolean shouldDesugarInterfaceMethods() {
    return delegate.getBoolean(SECTION, "desugar_interface_methods").orElse(false);
  }

  public JavacSpec getJavacSpec(TargetConfiguration targetConfiguration) {
    return javacSpecSupplier.apply(targetConfiguration);
  }

  @VisibleForTesting
  Optional<SourcePath> getJavacPath(TargetConfiguration targetConfiguration) {
    Optional<SourcePath> sourcePath = delegate.getSourcePath("tools", "javac", targetConfiguration);
    if (sourcePath.isPresent() && sourcePath.get() instanceof PathSourcePath) {
      PathSourcePath pathSourcePath = (PathSourcePath) sourcePath.get();
      if (!pathSourcePath.getFilesystem().isExecutable(pathSourcePath.getRelativePath())) {
        throw new HumanReadableException("javac is not executable: %s", pathSourcePath);
      }
    }
    return sourcePath;
  }

  private Optional<SourcePath> getJavacJarPath(TargetConfiguration targetConfiguration) {
    return delegate.getSourcePath("tools", "javac_jar", targetConfiguration);
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
                              Objects.requireNonNull(delegate.getPathSourcePath(path))))
                  .build();
            });
  }

  public boolean shouldCacheBinaries() {
    return delegate.getBooleanValue(SECTION, "cache_binaries", true);
  }

  public OptionalInt getDxThreadCount() {
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

  public UnusedDependenciesAction getUnusedDependenciesAction() {
    return delegate
        .getEnum(SECTION, "unused_dependencies_action", UnusedDependenciesAction.class)
        .orElse(UnusedDependenciesAction.IGNORE);
  }

  public Optional<String> getJavaTempDir() {
    return delegate.getValue("java", "test_temp_dir");
  }

  public Level getDuplicatesLogLevel() {
    return delegate
        .getEnum(SECTION, "duplicates_log_level", DuplicatesLogLevel.class)
        .orElse(DuplicatesLogLevel.INFO)
        .getLevel();
  }

  public enum SourceAbiVerificationMode {
    /** Don't verify ABI jars. */
    OFF,
    /** Generate ABI jars from classes and from source. Log any differences. */
    LOG,
    /** Generate ABI jars from classes and from source. Fail on differences. */
    FAIL,
  }

  /** An action that is executed when a rule that compiles Java code has unused dependencies. */
  public enum UnusedDependenciesAction {
    FAIL,
    WARN,
    IGNORE
  }

  /** Logging level duplicates are reported at */
  public enum DuplicatesLogLevel {
    WARN(Level.WARNING),
    INFO(Level.INFO),
    FINE(Level.FINE),
    ;

    private final Level level;

    DuplicatesLogLevel(Level level) {
      this.level = level;
    }

    public Level getLevel() {
      return level;
    }
  }
}
