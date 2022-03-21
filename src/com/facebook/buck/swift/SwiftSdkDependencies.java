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

package com.facebook.buck.swift;

import com.facebook.buck.apple.common.AppleCompilerTargetTriple;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.swift.toolchain.ExplicitModuleOutput;
import com.facebook.buck.swift.toolchain.SwiftSdkDependenciesProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Parser for Swift SDK dependency info JSON. The expected input can be generated using the script
 * in `scripts/generate_swift_sdk_dependencies.py`.
 */
public class SwiftSdkDependencies implements SwiftSdkDependenciesProvider {

  private final SdkDependencyJson sdkDependencies;

  private final ImmutableMap<String, ClangModule> clangModules;

  private final ImmutableMap<String, SwiftModule> swiftModules;

  private final ImmutableMap<String, String> linkNameMap;

  private final LoadingCache<CacheKey, ImmutableSet<ExplicitModuleOutput>>
      swiftBuildRuleDependencyCache;

  private final LoadingCache<CacheKey, ImmutableSet<ExplicitModuleOutput>>
      clangBuildRuleDependencyCache;

  private final BaseName swiftTargetBaseName;

  private final BaseName clangTargetBaseName;

  private final String ResourceDirPrefix = "$RESOURCEDIR/";

  private final String PlatformDirPrefix = "$PLATFORM_DIR/";

  private final String SdkRootPrefix = "$SDKROOT/";

  public SwiftSdkDependencies(
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      String sdkDependenciesPath,
      Tool swiftc,
      ImmutableList<Arg> swiftFlags,
      AppleCompilerTargetTriple triple,
      SourcePath sdkPath,
      SourcePath platformPath,
      SourcePath swiftResourceDir)
      throws HumanReadableException {
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      sdkDependencies =
          objectMapper.readValue(
              projectFilesystem.resolve(sdkDependenciesPath).toFile(), SdkDependencyJson.class);
    } catch (IOException ex) {
      throw new HumanReadableException(
          "Failed to parse SDK dependencies info: " + ex.getLocalizedMessage());
    }

    // Construct the target base names to use for this SDK and toolchain.
    swiftTargetBaseName =
        BaseName.of(
            "//_swift_runtime/sdk/"
                + sdkDependencies.getSdkVersion()
                + "/"
                + triple.getPlatformName()
                + "/"
                + triple.getArchitecture()
                + "/swift");
    clangTargetBaseName =
        BaseName.of(
            "//_swift_runtime/sdk/"
                + sdkDependencies.getSdkVersion()
                + "/"
                + triple.getPlatformName()
                + "/"
                + triple.getArchitecture()
                + "/clang");

    ImmutableMap.Builder<String, ClangModule> clangModulesBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<String, String> linkNameBuilder = ImmutableMap.builder();
    for (ClangModule module : sdkDependencies.getClangDependencies()) {
      clangModulesBuilder.put(module.getName(), module);
      if (module.getLinkName() != null) {
        linkNameBuilder.put(module.getLinkName(), module.getName());
      }
    }
    clangModules = clangModulesBuilder.build();
    linkNameMap = linkNameBuilder.build();

    ImmutableMap.Builder<String, SwiftModule> modulesBuilder = ImmutableMap.builder();
    for (SwiftModule module : sdkDependencies.getSwiftDependencies()) {
      modulesBuilder.put(module.getName(), module);
    }
    swiftModules = modulesBuilder.build();

    swiftBuildRuleDependencyCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<>() {
                  @Override
                  public ImmutableSet<ExplicitModuleOutput> load(CacheKey key) {
                    return getSwiftmoduleDependencies(
                        key,
                        graphBuilder,
                        projectFilesystem,
                        swiftc,
                        swiftFlags,
                        sdkPath,
                        platformPath,
                        swiftResourceDir);
                  }
                });

    clangBuildRuleDependencyCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<>() {
                  @Override
                  public ImmutableSet<ExplicitModuleOutput> load(CacheKey key) {
                    return getClangModuleDependencies(
                        key,
                        graphBuilder,
                        projectFilesystem,
                        swiftc,
                        swiftFlags,
                        sdkPath,
                        platformPath,
                        swiftResourceDir);
                  }
                });
  }

  public SwiftModule getSwiftModule(String moduleName) {
    return swiftModules.get(moduleName);
  }

  public String getModuleNameForLinkName(String linkName) {
    // Link name should not include the lib prefix.
    if (linkName.startsWith("lib")) {
      linkName = linkName.substring(3);
    }

    if (linkNameMap.containsKey(linkName)) {
      return linkNameMap.get(linkName);
    } else {
      // Default to the link name for the module name, this is the common case.
      return linkName;
    }
  }

  @Override
  public ImmutableSet<ExplicitModuleOutput> getSdkModuleDependencies(
      String moduleName, AppleCompilerTargetTriple targetTriple) {
    if (swiftModules.containsKey(moduleName)) {
      return swiftBuildRuleDependencyCache.getUnchecked(
          CacheKey.of(moduleName, targetTriple.getVersionedTriple()));
    } else {
      return getSdkClangModuleDependencies(moduleName, targetTriple);
    }
  }

  @Override
  public ImmutableSet<ExplicitModuleOutput> getSdkClangModuleDependencies(
      String moduleName, AppleCompilerTargetTriple targetTriple) {
    if (clangModules.containsKey(moduleName)) {
      return clangBuildRuleDependencyCache.getUnchecked(
          CacheKey.of(moduleName, targetTriple.getVersionedTriple()));
    } else {
      return ImmutableSet.of();
    }
  }

  private ImmutableSet<ExplicitModuleOutput> getSwiftmoduleDependencies(
      CacheKey key,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      Tool swiftc,
      ImmutableList<Arg> swiftFlags,
      SourcePath sdkPath,
      SourcePath platformPath,
      SourcePath resourceDir) {
    SwiftModule module = getSwiftModule(key.getName());

    /**
     * For every Swift SDK dependency we need to create a build rule that produces a .swiftmodule
     * file that we can use as input in a `SwiftCompile` rule. These rules are created dynamically
     * based on information provided in the `swift_toolchain` rule via the `sdk_dependencies_path`
     * attribute. Each `SwiftInterfaceCompile` rule transitively depends on the output of its
     * dependent `SwiftInterfaceCompile` rules to compile successfully. As these rules are
     * independent of target configuration we create them as unconfigured targets and cache the
     * transitive set of outputs for each framework dependency.
     *
     * <p>An alternative approach would be to model the SDK dependencies with a new target type.
     * This solution seems initially appealing, but presents some problems: - every single `apple_*`
     * target would have to be updated to add the correct dependencies on the SDK targets, a
     * daunting prospect at this point - the SDK dependencies are different for each SDK version and
     * platform. This would require introducing constraints for the SDK version used for each
     * application as well as the platform and architecture, and then making the `exported_deps` of
     * each SDK target a nested `select` that defined the correct dependencies. - the SDK dependency
     * targets would somehow have to reference paths inside an SDK which could either come from
     * Xcode or a build rule.
     *
     * <p>This approach was considered more complex and fragile, and a signficant investment in
     * refactoring the existing build graph. It may be worth revisiting this approach if we move
     * away from Xcode toolchain selection, as we would then need to model a target SDK in the graph
     * more explicitly.
     */

    // SwiftInterfaceCompile rules are already specific to target SDK version via the target name,
    // the compiler target is not used.
    int hashCode =
        swiftc.getCommandPrefix(graphBuilder.getSourcePathResolver()).hashCode()
            ^ Arg.stringify(swiftFlags, graphBuilder.getSourcePathResolver()).hashCode();
    Flavor swiftFlavor = InternalFlavor.of(Integer.toHexString(hashCode));
    UnconfiguredBuildTarget unconfiguredBuildTarget =
        UnconfiguredBuildTarget.of(swiftTargetBaseName, key.getName());
    BuildTarget buildTarget =
        unconfiguredBuildTarget
            .configure(UnconfiguredTargetConfiguration.INSTANCE)
            .withFlavors(swiftFlavor);

    BuildRule rule =
        graphBuilder.computeIfAbsent(
            buildTarget,
            target -> {
              // Collect the transitive Swift and Clang module dependencies for the target
              // triple used by this Swift module.
              ImmutableSet.Builder<ExplicitModuleOutput> moduleDepsBuilder = ImmutableSet.builder();
              for (String dep : module.getSwiftDependencies()) {
                moduleDepsBuilder.addAll(
                    swiftBuildRuleDependencyCache.getUnchecked(
                        CacheKey.of(dep, module.getTarget())));
              }

              for (String dep : module.getClangDependencies()) {
                moduleDepsBuilder.addAll(
                    clangBuildRuleDependencyCache.getUnchecked(
                        CacheKey.of(dep, module.getTarget())));
              }

              return new SwiftInterfaceCompile(
                  target,
                  projectFilesystem,
                  graphBuilder,
                  swiftc,
                  swiftFlags,
                  false,
                  module.getName(),
                  replacePathPrefix(
                      module.getSwiftInterfacePath(), sdkPath, platformPath, resourceDir),
                  moduleDepsBuilder.build());
            });

    ImmutableSet.Builder<ExplicitModuleOutput> depsBuilder = ImmutableSet.builder();
    depsBuilder.add(
        ExplicitModuleOutput.of(
            key.getName(), true, rule.getSourcePathToOutput(), module.isFramework()));

    // We need to collect the transitive dependencies at the specified target triple, which
    // is most likely different from this modules target.
    for (String dep : module.getSwiftDependencies()) {
      depsBuilder.addAll(
          swiftBuildRuleDependencyCache.getUnchecked(CacheKey.of(dep, key.getCompilerTarget())));
    }

    for (String dep : module.getClangDependencies()) {
      depsBuilder.addAll(
          clangBuildRuleDependencyCache.getUnchecked(CacheKey.of(dep, key.getCompilerTarget())));
    }

    return depsBuilder.build();
  }

  private ImmutableSet<ExplicitModuleOutput> getClangModuleDependencies(
      CacheKey key,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      Tool swiftc,
      ImmutableList<Arg> swiftFlags,
      SourcePath sdkPath,
      SourcePath platformPath,
      SourcePath swiftResourceDir) {
    ClangModule clangModule = clangModules.get(key.getName());

    // Recursively collect dependencies at the given compiler target.
    ImmutableSet.Builder<ExplicitModuleOutput> depsBuilder = ImmutableSet.builder();
    for (String dep : clangModule.getClangDependencies()) {
      depsBuilder.addAll(
          clangBuildRuleDependencyCache.getUnchecked(CacheKey.of(dep, key.getCompilerTarget())));
    }

    // Clang module compilation is specific to the compiler target, so include that in the
    // target flavor.
    int hashCode =
        swiftc.getCommandPrefix(graphBuilder.getSourcePathResolver()).hashCode()
            ^ Arg.stringify(swiftFlags, graphBuilder.getSourcePathResolver()).hashCode()
            ^ key.getCompilerTarget().hashCode();
    Flavor clangFlavor = InternalFlavor.of(Integer.toHexString(hashCode));
    UnconfiguredBuildTarget unconfiguredBuildTarget =
        UnconfiguredBuildTarget.of(clangTargetBaseName, key.getName());
    BuildTarget buildTarget =
        unconfiguredBuildTarget
            .configure(UnconfiguredTargetConfiguration.INSTANCE)
            .withFlavors(clangFlavor);

    BuildRule rule =
        graphBuilder.computeIfAbsent(
            buildTarget,
            target ->
                new SwiftModuleMapCompile(
                    target,
                    projectFilesystem,
                    graphBuilder,
                    key.getCompilerTarget(),
                    swiftc,
                    swiftFlags,
                    false,
                    key.getName(),
                    true,
                    replacePathPrefix(
                        clangModule.getModulemapPath(), sdkPath, platformPath, swiftResourceDir),
                    depsBuilder.build()));

    depsBuilder.add(ExplicitModuleOutput.of(key.getName(), false, rule.getSourcePathToOutput()));
    return depsBuilder.build();
  }

  private ExplicitModuleInput replacePathPrefix(
      Path path, SourcePath sdkPath, SourcePath platformPath, SourcePath resourceDir) {
    if (path.startsWith(SdkRootPrefix)) {
      return ExplicitModuleInput.of(sdkPath, path.subpath(1, path.getNameCount()));
    } else if (path.startsWith(ResourceDirPrefix)) {
      return ExplicitModuleInput.of(resourceDir, path.subpath(1, path.getNameCount()));
    } else if (path.startsWith(PlatformDirPrefix)) {
      return ExplicitModuleInput.of(platformPath, path.subpath(1, path.getNameCount()));
    } else {
      throw new HumanReadableException("Unknown SDK dependency path prefix: " + path);
    }
  }

  /** A pair class used for the SDK dependencies that need to cache per target. */
  @BuckStyleValue
  abstract static class CacheKey {
    /** The module name we are compiling dependencies for. */
    abstract String getName();

    /** The compiler target used to compile the modules. */
    abstract String getCompilerTarget();

    static CacheKey of(String name, String target) {
      return ImmutableCacheKey.ofImpl(name, target);
    }
  }

  /** A class that represents a Swift module dependency of an Apple SDK. */
  static class SwiftModule implements Comparable<SwiftModule> {
    private String name;

    private String target;

    @JsonProperty("is_framework")
    private boolean isFramework;

    @JsonProperty("swiftinterface")
    private Path swiftInterfacePath;

    @JsonProperty("swift_deps")
    private List<String> swiftDependencies;

    @JsonProperty("clang_deps")
    private List<String> clangDependencies;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getTarget() {
      return target;
    }

    public void setTarget(String target) {
      this.target = target;
    }

    public boolean isFramework() {
      return isFramework;
    }

    public void setFramework(boolean framework) {
      isFramework = framework;
    }

    public Path getSwiftInterfacePath() {
      return swiftInterfacePath;
    }

    public void setSwiftInterfacePath(Path swiftInterfacePath) {
      this.swiftInterfacePath = swiftInterfacePath;
    }

    public List<String> getSwiftDependencies() {
      return swiftDependencies;
    }

    public void setSwiftDependencies(List<String> swiftDependencies) {
      this.swiftDependencies = swiftDependencies;
    }

    public List<String> getClangDependencies() {
      return clangDependencies;
    }

    public void setClangDependencies(List<String> clangDependencies) {
      this.clangDependencies = clangDependencies;
    }

    @Override
    public int compareTo(SwiftModule other) {
      return name.compareTo(other.getName());
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SwiftModule that = (SwiftModule) o;
      return name.equals(that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name);
    }
  }

  /** A class that represents a Clang module dependency of an Apple SDK. */
  static class ClangModule {
    private String name;

    @JsonProperty("modulemap")
    private Path modulemapPath;

    @JsonProperty("clang_deps")
    private List<String> clangDependencies;

    @JsonProperty("link_name")
    private String linkName;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public Path getModulemapPath() {
      return modulemapPath;
    }

    public void setModulemapPath(Path modulemapPath) {
      this.modulemapPath = modulemapPath;
    }

    public List<String> getClangDependencies() {
      return clangDependencies;
    }

    public void setClangDependencies(List<String> clangDependencies) {
      this.clangDependencies = clangDependencies;
    }

    public String getLinkName() {
      return linkName;
    }

    public void setLinkName(String linkName) {
      this.linkName = linkName;
    }
  }

  @SuppressWarnings("unused")
  private static class SdkDependencyJson {
    @JsonProperty("sdk_version")
    private String sdkVersion;

    @JsonProperty("swift")
    private List<SwiftModule> swiftDependencies;

    @JsonProperty("clang")
    private List<ClangModule> clangDependencies;

    public String getSdkVersion() {
      return sdkVersion;
    }

    public void setSdkVersion(String sdkVersion) {
      this.sdkVersion = sdkVersion;
    }

    public List<SwiftModule> getSwiftDependencies() {
      return swiftDependencies;
    }

    public void setSwiftDependencies(List<SwiftModule> swiftDependencies) {
      this.swiftDependencies = swiftDependencies;
    }

    public List<ClangModule> getClangDependencies() {
      return clangDependencies;
    }

    public void setClangDependencies(List<ClangModule> clangDependencies) {
      this.clangDependencies = clangDependencies;
    }
  }
}
