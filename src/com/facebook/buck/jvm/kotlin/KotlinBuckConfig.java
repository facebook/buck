/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.cd.model.java.AbiGenerationMode;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import javax.annotation.Nullable;

/** A kotlin-specific "view" of BuckConfig. */
public class KotlinBuckConfig implements ConfigView<BuckConfig> {

  private static final Logger LOGGER = Logger.get(KotlinBuckConfig.class);
  private static final boolean IS_WINDOWS = Platform.detect() == Platform.WINDOWS;

  private static final String SECTION = "kotlin";
  public static final String PROPERTY_COMPILE_AGAINST_ABIS = "compile_against_abis";
  public static final String PROPERTY_ABI_GENERATION_MODE = "abi_generation_mode";
  public static final String PROPERTY_GENERATE_ANNOTATION_PROCESSING_STATS =
      "generate_annotation_processing_stats";
  public static final String PROPERTY_KOTLINCD_ENABLED = "kotlincd_enabled";
  static final String PROPERTY_KOTLINCD_DISABLED_FOR_WINDOWS = "kotlincd_disabled_for_windows";

  /**
   * Libraries that are found in KOTLIN_HOME (under their {@link KotlinHomeLibrary#jarName}), but
   * can be overridden by a config in the [kotlin] section, (field {@link
   * KotlinHomeLibrary#fieldName}).
   */
  public enum KotlinHomeLibrary {
    ANNOTATIONS("toolchain_annotations_jar", "annotations-13.0.jar"),
    COMPILER("toolchain_compiler_jar", "kotlin-compiler.jar"),
    KAPT("toolchain_kapt_jar", "kotlin-annotation-processing.jar"),
    REFLECT("toolchain_reflect_jar", "kotlin-reflect.jar"),
    SCRIPT_RUNTIME("toolchain_script_runtime_jar", "kotlin-script-runtime.jar"),
    STDLIB("toolchain_stdlib_jar", "kotlin-stdlib.jar"),
    TROVE4J("toolchain_trove4j_jar", "trove4j.jar");

    KotlinHomeLibrary(String fieldName, String jarName) {
      this.fieldName = fieldName;
      this.jarName = jarName;
    }

    public final String fieldName;
    public final String jarName;
  }

  private static final Path DEFAULT_KOTLIN_COMPILER = Paths.get("kotlinc");

  private final BuckConfig delegate;
  private @Nullable Path kotlinHome;

  public KotlinBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Kotlinc getKotlinc() {
    if (isExternalCompilation()) {
      return new ExternalKotlinc(getPathToCompilerBinary());
    } else {
      return new JarBackedReflectedKotlinc();
    }
  }

  public ImmutableSortedSet<SourcePath> getKotlinHomeLibraries(
      TargetConfiguration targetConfiguration) {
    return ImmutableSortedSet.of(
        getPathToStdlibJar(targetConfiguration),
        getPathToReflectJar(targetConfiguration),
        getPathToScriptRuntimeJar(targetConfiguration),
        getPathToCompilerJar(targetConfiguration),
        getPathToTrove4jJar(targetConfiguration),
        getPathToAnnotationsJar(targetConfiguration));
  }

  /**
   * KOTLIN_HOME libraries can be overridden with either paths or targets. This function returns the
   * set of targets that need to be built to make all such libraries available.
   */
  public ImmutableSortedSet<BuildTarget> getKotlinHomeLibraryTargets(
      TargetConfiguration targetConfiguration) {
    ImmutableSortedSet.Builder<BuildTarget> targets =
        new ImmutableSortedSet.Builder<>(Comparator.naturalOrder());

    for (KotlinHomeLibrary library : KotlinHomeLibrary.values()) {
      delegate
          .getMaybeBuildTarget(SECTION, library.fieldName, targetConfiguration)
          .ifPresent(targets::add);
    }

    return targets.build();
  }

  public boolean shouldCompileAgainstAbis() {
    return delegate.getBooleanValue(SECTION, PROPERTY_COMPILE_AGAINST_ABIS, false);
  }

  public AbiGenerationMode getAbiGenerationMode() {
    return delegate
        .getEnum(SECTION, PROPERTY_ABI_GENERATION_MODE, AbiGenerationMode.class)
        .orElse(AbiGenerationMode.CLASS);
  }

  public boolean shouldGenerateAnnotationProcessingStats() {
    return delegate.getBooleanValue(SECTION, PROPERTY_GENERATE_ANNOTATION_PROCESSING_STATS, false);
  }

  public boolean isKotlinCDEnabled() {
    if (IS_WINDOWS && isKotlinCDDisabledForWindows()) {
      LOGGER.info("kotlincd disabled on windows");
      return false;
    }

    return delegate.getBooleanValue(SECTION, PROPERTY_KOTLINCD_ENABLED, false);
  }

  public boolean isKotlinCDDisabledForWindows() {
    return delegate.getBooleanValue(SECTION, PROPERTY_KOTLINCD_DISABLED_FOR_WINDOWS, false);
  }

  Path getPathToCompilerBinary() {
    Path compilerPath = getKotlinHome().resolve("kotlinc");
    if (!Files.isExecutable(compilerPath)) {
      compilerPath = getKotlinHome().resolve(Paths.get("bin", "kotlinc"));
      if (!Files.isExecutable(compilerPath)) {
        throw new HumanReadableException("Could not resolve kotlinc location.");
      }
    }

    return new ExecutableFinder().getExecutable(compilerPath, delegate.getEnvironment());
  }

  private SourcePath getPathToJar(
      TargetConfiguration targetConfiguration, KotlinHomeLibrary library) {
    Optional<SourcePath> jarOverride =
        delegate.getSourcePath(SECTION, library.fieldName, targetConfiguration);
    if (jarOverride.isPresent()) {
      return jarOverride.get();
    }

    Path reflect = getKotlinHome().resolve(library.jarName);
    if (Files.isRegularFile(reflect)) {
      return getSourcePathFromAbsolutePath(reflect);
    }

    reflect = getKotlinHome().resolve(Paths.get("lib", library.jarName));
    if (Files.isRegularFile(reflect)) {
      return getSourcePathFromAbsolutePath(reflect);
    }

    reflect = getKotlinHome().resolve(Paths.get("libexec", "lib", library.jarName));
    if (Files.isRegularFile(reflect)) {
      return getSourcePathFromAbsolutePath(reflect);
    }

    throw new HumanReadableException(
        "Could not resolve "
            + library.jarName
            + " JAR location (kotlin home:"
            + getKotlinHome()
            + ").");
  }

  private SourcePath getSourcePathFromAbsolutePath(Path path) {
    Path normalizedPath = path.normalize();
    Preconditions.checkArgument(normalizedPath.isAbsolute());
    RelPath relPath =
        ProjectFilesystemUtils.relativize(delegate.getFilesystem().getRootPath(), normalizedPath);

    return delegate.getPathSourcePath(relPath.getPath());
  }

  /**
   * Get the path to the Kotlin runtime jar.
   *
   * @return the Kotlin runtime jar path
   */
  SourcePath getPathToStdlibJar(TargetConfiguration targetConfiguration) {
    return getPathToJar(targetConfiguration, KotlinHomeLibrary.STDLIB);
  }

  /**
   * Get the path to the Kotlin reflection jar.!
   *
   * @return the Kotlin reflection jar path
   */
  SourcePath getPathToReflectJar(TargetConfiguration targetConfiguration) {
    return getPathToJar(targetConfiguration, KotlinHomeLibrary.REFLECT);
  }

  /**
   * Get the path to the Kotlin script runtime jar.
   *
   * @return the Kotlin script runtime jar path
   */
  SourcePath getPathToScriptRuntimeJar(TargetConfiguration targetConfiguration) {
    return getPathToJar(targetConfiguration, KotlinHomeLibrary.SCRIPT_RUNTIME);
  }

  /**
   * Get the path to the Kotlin compiler jar.
   *
   * @return the Kotlin compiler jar path
   */
  SourcePath getPathToCompilerJar(TargetConfiguration targetConfiguration) {
    return getPathToJar(targetConfiguration, KotlinHomeLibrary.COMPILER);
  }

  /**
   * Get the path to the trove4j jar, which is required by the compiler jar.
   *
   * @return the trove4j jar path
   */
  SourcePath getPathToTrove4jJar(TargetConfiguration targetConfiguration) {
    return getPathToJar(targetConfiguration, KotlinHomeLibrary.TROVE4J);
  }

  /**
   * Get the path to the annotations jar, which is required by the compiler jar.
   *
   * @return the annotations jar path
   */
  SourcePath getPathToAnnotationsJar(TargetConfiguration targetConfiguration) {
    return getPathToJar(targetConfiguration, KotlinHomeLibrary.ANNOTATIONS);
  }

  /**
   * Get the path to the Kotlin annotation processing jar.
   *
   * @return the Kotlin annotation processing jar path
   */
  SourcePath getPathToAnnotationProcessingJar(TargetConfiguration targetConfiguration) {
    return getPathToJar(targetConfiguration, KotlinHomeLibrary.KAPT);
  }

  /**
   * Determine whether dep-based rule keys are used for Kotlin.
   *
   * @return true to use dep-based rule keys, false otherwise
   */
  boolean trackClassUsage() {
    return delegate.getBoolean(SECTION, "track_class_usage").orElse(false);
  }

  /**
   * Determine whether dep-based rule keys are used for Kotlin targets with KAPT.
   *
   * @return true to use dep-based rule keys, false otherwise
   */
  boolean trackClassUsageForKaptTargets() {
    return delegate.getBoolean(SECTION, "track_class_usage_for_kapt_targets").orElse(false);
  }

  /**
   * Determine whether dep-based rule keys are used for Kotlin targets with KSP.
   *
   * @return true to use dep-based rule keys, false otherwise
   */
  boolean trackClassUsageForKspTargets() {
    return delegate.getBoolean(SECTION, "track_class_usage_for_ksp_targets").orElse(false);
  }

  /**
   * Disable dep-based rule keys for targets with certain processors
   *
   * @return true to use dep-based rule keys, false otherwise
   */
  ImmutableList<String> trackClassUsageProcessorBlocklist() {
    return delegate.getListWithoutComments(SECTION, "track_class_usage_processor_blocklist");
  }

  /**
   * Get the action to perform when unused dependencies are detected. This can be set in .buckconfig
   * via "unused_dependencies_action" property; possible values are "ignore", "warn", "fail",
   * "ignore_always", "warn_if_fail". Default is "ignore".
   *
   * @return the action to perform when unused dependencies are detected
   */
  public JavaBuckConfig.UnusedDependenciesConfig getUnusedDependenciesAction() {
    return delegate
        .getEnum(
            SECTION, "unused_dependencies_action", JavaBuckConfig.UnusedDependenciesConfig.class)
        .orElse(JavaBuckConfig.UnusedDependenciesConfig.IGNORE);
  }

  /**
   * Determine whether external Kotlin compilation is being forced. The default is internal
   * (in-process) execution, but this can be overridden in .buckconfig by setting the "external"
   * property to "true".
   *
   * @return true is external compilation is requested, false otherwise
   */
  private boolean isExternalCompilation() {
    Optional<Boolean> value = delegate.getBoolean(SECTION, "external");
    return value.orElse(false);
  }

  /**
   * Find the Kotlin home (installation) directory by searching in this order: <br>
   *
   * <ul>
   *   <li>If the "kotlin_home" directory is specified in .buckconfig then use it.
   *   <li>Check the environment for a KOTLIN_HOME variable, if defined then use it.
   *   <li>Resolve "kotlinc" with an ExecutableFinder, and if found then deduce the kotlin home
   *       directory from it.
   * </ul>
   *
   * @return the Kotlin home path
   */
  private Path getKotlinHome() {
    if (kotlinHome != null) {
      return kotlinHome;
    }

    try {
      // Check the buck configuration for a specified kotlin home
      Optional<String> value = delegate.getValue(SECTION, "kotlin_home");

      if (value.isPresent()) {
        boolean isAbsolute = Paths.get(value.get()).isAbsolute();
        Optional<Path> homePath = delegate.getPath(SECTION, "kotlin_home", !isAbsolute);
        if (homePath.isPresent() && Files.isDirectory(homePath.get())) {
          return homePath.get().toRealPath().normalize();
        } else {
          throw new HumanReadableException(
              "Kotlin home directory (" + homePath + ") specified in .buckconfig was not found.");
        }
      } else {
        // If the KOTLIN_HOME environment variable is specified we trust it
        String home = delegate.getEnvironment().get("KOTLIN_HOME");
        if (home != null) {
          return Paths.get(home).normalize();
        } else {
          // Lastly, we try to resolve from the system PATH
          Optional<Path> compiler =
              new ExecutableFinder()
                  .getOptionalExecutable(DEFAULT_KOTLIN_COMPILER, delegate.getEnvironment());
          if (compiler.isPresent()) {
            kotlinHome = compiler.get().toRealPath().getParent().normalize();
            if (kotlinHome != null && kotlinHome.endsWith(Paths.get("bin"))) {
              kotlinHome = kotlinHome.getParent().normalize();
            }
            return kotlinHome;
          } else {
            throw new HumanReadableException(
                "Could not resolve kotlin home directory, Consider setting KOTLIN_HOME.");
          }
        }
      }
    } catch (IOException io) {
      throw new HumanReadableException(
          "Could not resolve kotlin home directory, Consider setting KOTLIN_HOME.", io);
    }
  }

  @Override
  public BuckConfig getDelegate() {
    return delegate;
  }
}
