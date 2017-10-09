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

package com.facebook.buck.python;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PackagedResource;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.annotation.Nonnull;

public class PythonBuckConfig {

  public static final Flavor DEFAULT_PYTHON_PLATFORM = InternalFlavor.of("py-default");

  private static final String SECTION = "python";
  private static final String PYTHON_PLATFORM_SECTION_PREFIX = "python#";

  // Prefer "python2" where available (Linux), but fall back to "python" (Mac).
  private static final ImmutableList<String> PYTHON_INTERPRETER_NAMES =
      ImmutableList.of("python2", "python");

  private static final Path DEFAULT_PATH_TO_PEX =
      Paths.get(System.getProperty("buck.path_to_pex", "src/com/facebook/buck/python/make_pex.py"))
          .toAbsolutePath();

  private static final LoadingCache<ProjectFilesystem, PathSourcePath> PATH_TO_TEST_MAIN =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<ProjectFilesystem, PathSourcePath>() {
                @Override
                public PathSourcePath load(@Nonnull ProjectFilesystem filesystem) {
                  return PathSourcePath.of(
                      filesystem,
                      PythonBuckConfig.class + "/__test_main__.py",
                      new PackagedResource(filesystem, PythonBuckConfig.class, "__test_main__.py"));
                }
              });

  private final BuckConfig delegate;
  private final ExecutableFinder exeFinder;

  public PythonBuckConfig(BuckConfig config, ExecutableFinder exeFinder) {
    this.delegate = config;
    this.exeFinder = exeFinder;
  }

  @VisibleForTesting
  protected PythonPlatform getDefaultPythonPlatform(ProcessExecutor executor)
      throws InterruptedException {
    return getPythonPlatform(executor, SECTION, DEFAULT_PYTHON_PLATFORM);
  }

  /**
   * Constructs set of Python platform flavors given in a .buckconfig file, as is specified by
   * section names of the form python#{flavor name}.
   */
  public ImmutableList<PythonPlatform> getPythonPlatforms(ProcessExecutor processExecutor)
      throws InterruptedException {
    ImmutableList.Builder<PythonPlatform> builder = ImmutableList.builder();

    // Add the python platform described in the top-level section first.
    builder.add(getDefaultPythonPlatform(processExecutor));

    // Then add all additional python platform described in the extended sections.
    for (String section : delegate.getSections()) {
      if (section.startsWith(PYTHON_PLATFORM_SECTION_PREFIX)) {
        builder.add(
            getPythonPlatform(
                processExecutor,
                section,
                InternalFlavor.of(section.substring(PYTHON_PLATFORM_SECTION_PREFIX.length()))));
      }
    }

    return builder.build();
  }

  private PythonPlatform getPythonPlatform(
      ProcessExecutor processExecutor, String section, Flavor flavor) throws InterruptedException {
    return PythonPlatform.of(
        flavor,
        getPythonEnvironment(processExecutor, section),
        delegate.getBuildTarget(section, "library"));
  }

  private Path findInterpreter(ImmutableList<String> interpreterNames) {
    Preconditions.checkArgument(!interpreterNames.isEmpty());
    for (String interpreterName : interpreterNames) {
      Optional<Path> python =
          exeFinder.getOptionalExecutable(Paths.get(interpreterName), delegate.getEnvironment());
      if (python.isPresent()) {
        return python.get().toAbsolutePath();
      }
    }
    throw new HumanReadableException(
        "No python interpreter found (searched %s).", Joiner.on(", ").join(interpreterNames));
  }

  /**
   * Returns the path to python interpreter. If python is specified in 'interpreter' key of the
   * 'python' section that is used and an error reported if invalid.
   *
   * @return The found python interpreter.
   */
  public Path getPythonInterpreter(Optional<String> config) {
    if (!config.isPresent()) {
      return findInterpreter(PYTHON_INTERPRETER_NAMES);
    }
    Path configPath = Paths.get(config.get());
    if (!configPath.isAbsolute()) {
      return findInterpreter(ImmutableList.of(config.get()));
    }
    return configPath;
  }

  private Path getPythonInterpreter(String section) {
    return getPythonInterpreter(delegate.getValue(section, "interpreter"));
  }

  /** @return the {@link Path} to the default python interpreter. */
  public Path getPythonInterpreter() {
    return getPythonInterpreter(SECTION);
  }

  private PythonEnvironment getPythonEnvironment(ProcessExecutor processExecutor, String section)
      throws InterruptedException {
    Path pythonPath = getPythonInterpreter(section);
    PythonVersion pythonVersion = getVersion(processExecutor, section, pythonPath);
    return new PythonEnvironment(pythonPath, pythonVersion);
  }

  public PythonEnvironment getPythonEnvironment(ProcessExecutor processExecutor)
      throws InterruptedException {
    return getPythonEnvironment(processExecutor, SECTION);
  }

  public SourcePath getPathToTestMain(ProjectFilesystem filesystem) {
    return PATH_TO_TEST_MAIN.getUnchecked(filesystem);
  }

  public Optional<BuildTarget> getPexTarget() {
    return delegate.getMaybeBuildTarget(SECTION, "path_to_pex");
  }

  public Tool getPexTool(BuildRuleResolver resolver) {
    CommandTool.Builder builder = new CommandTool.Builder(getRawPexTool(resolver));
    for (String flag :
        Splitter.on(' ')
            .omitEmptyStrings()
            .split(delegate.getValue(SECTION, "pex_flags").orElse(""))) {
      builder.addArg(flag);
    }

    return builder.build();
  }

  private Tool getRawPexTool(BuildRuleResolver resolver) {
    Optional<Tool> executable =
        delegate.getView(ToolConfig.class).getTool(SECTION, "path_to_pex", resolver);
    if (executable.isPresent()) {
      return executable.get();
    }
    return VersionedTool.builder()
        .setName("pex")
        .setVersion(BuckVersion.getVersion())
        .setPath(getPythonInterpreter(SECTION))
        .addExtraArgs(DEFAULT_PATH_TO_PEX.toString())
        .build();
  }

  public Optional<BuildTarget> getPexExecutorTarget() {
    return delegate.getMaybeBuildTarget(SECTION, "path_to_pex_executer");
  }

  public Optional<Tool> getPexExecutor(BuildRuleResolver resolver) {
    return delegate.getView(ToolConfig.class).getTool(SECTION, "path_to_pex_executer", resolver);
  }

  public NativeLinkStrategy getNativeLinkStrategy() {
    return delegate
        .getEnum(SECTION, "native_link_strategy", NativeLinkStrategy.class)
        .orElse(NativeLinkStrategy.SEPARATE);
  }

  public String getPexExtension() {
    return delegate.getValue(SECTION, "pex_extension").orElse(".pex");
  }

  private Optional<PythonVersion> getConfiguredVersion(String section) {
    return delegate.getValue(section, "version").map(PythonVersion::fromString);
  }

  private PythonVersion getVersion(ProcessExecutor processExecutor, String section, Path path)
      throws InterruptedException {

    Optional<PythonVersion> configuredVersion = getConfiguredVersion(section);
    if (configuredVersion.isPresent()) {
      return configuredVersion.get();
    }

    return PythonVersion.fromInterpreter(processExecutor, path);
  }

  public boolean shouldCacheBinaries() {
    return delegate.getBooleanValue(SECTION, "cache_binaries", true);
  }

  public boolean legacyOutputPath() {
    return delegate.getBooleanValue(SECTION, "legacy_output_path", false);
  }

  public PackageStyle getPackageStyle() {
    return delegate
        .getEnum(SECTION, "package_style", PackageStyle.class)
        .orElse(PackageStyle.STANDALONE);
  }

  public enum PackageStyle {
    STANDALONE,
    INPLACE,
  }
}
