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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.swift.toolchain.SwiftSdkDependenciesProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Parser for Swift SDK dependency info JSON. The expected input can be generated using the script
 * in `scripts/generate_swift_sdk_dependencies.py`.
 */
public class SwiftSdkDependencies implements SwiftSdkDependenciesProvider {

  private final SdkDependencyJson sdkDependencies;

  private final ImmutableMap<String, SwiftModule> swiftModules;

  private final LoadingCache<String, ImmutableSet<SourcePath>> swiftBuildRuleDependencyCache;

  private final Flavor platformFlavor;

  private final BaseName targetBaseName;

  public SwiftSdkDependencies(
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      String sdkDependenciesPath,
      Tool swiftc,
      ImmutableList<Arg> swiftFlags,
      AppleCompilerTargetTriple triple,
      SourcePath sdkPath)
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

    // Construct the target base name and toolchain hash code to use for this SDK and toolchain.
    int hashCode =
        swiftc.getCommandPrefix(graphBuilder.getSourcePathResolver()).hashCode()
            ^ Arg.stringify(swiftFlags, graphBuilder.getSourcePathResolver()).hashCode();
    platformFlavor = InternalFlavor.of(Integer.toHexString(hashCode));
    targetBaseName =
        BaseName.of(
            "//_swift_runtime/sdk/"
                + sdkDependencies.getSdkVersion()
                + "/"
                + triple.getPlatformName()
                + "/"
                + triple.getArchitecture());

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
                  public ImmutableSet<SourcePath> load(String moduleName) {
                    return getSwiftmoduleDependencies(
                        graphBuilder,
                        projectFilesystem,
                        triple,
                        swiftc,
                        swiftFlags,
                        sdkPath,
                        moduleName);
                  }
                });
  }

  public SwiftModule getSwiftModule(String moduleName) {
    return swiftModules.get(moduleName);
  }

  @Override
  public ImmutableSet<SourcePath> getSwiftmoduleDependencyPaths(String moduleName) {
    return swiftBuildRuleDependencyCache.getUnchecked(moduleName);
  }

  private ImmutableSet<SourcePath> getSwiftmoduleDependencies(
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      AppleCompilerTargetTriple targetTriple,
      Tool swiftc,
      ImmutableList<Arg> swiftFlags,
      SourcePath sdkPath,
      String moduleName) {
    // Create build rules for all the dependent SDK modules of this one.
    HashSet<SourcePath> ruleOutputs = new HashSet<>();
    visitSwiftDependencies(
        ruleOutputs,
        graphBuilder,
        projectFilesystem,
        targetTriple,
        swiftc,
        swiftFlags,
        sdkPath,
        moduleName);

    // Cache the dependencies so we don't have to walk the graph again for this SDK module.
    return ImmutableSet.copyOf(ruleOutputs);
  }

  private void visitSwiftDependencies(
      Set<SourcePath> outputs,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      AppleCompilerTargetTriple targetTriple,
      Tool swiftc,
      ImmutableList<Arg> swiftFlags,
      SourcePath sdkPath,
      String moduleName) {
    SwiftModule swiftModule = getSwiftModule(moduleName);
    if (swiftModule == null) {
      // Framework uses a modulemap without swiftinterface
      return;
    }

    HashSet<SourcePath> transitiveOutputs = new HashSet<>();
    for (String dep : swiftModule.getSwiftDependencies()) {
      visitSwiftDependencies(
          transitiveOutputs,
          graphBuilder,
          projectFilesystem,
          targetTriple,
          swiftc,
          swiftFlags,
          sdkPath,
          dep);
    }

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
    UnconfiguredBuildTarget unconfiguredBuildTarget =
        UnconfiguredBuildTarget.of(targetBaseName, moduleName);
    BuildTarget buildTarget =
        unconfiguredBuildTarget
            .configure(UnconfiguredTargetConfiguration.INSTANCE)
            .withFlavors(platformFlavor);

    BuildRule rule =
        graphBuilder.computeIfAbsent(
            buildTarget,
            target -> {
              ImmutableSortedSet.Builder<SourcePath> deps = ImmutableSortedSet.naturalOrder();
              deps.addAll(transitiveOutputs);

              return new SwiftInterfaceCompile(
                  target,
                  projectFilesystem,
                  graphBuilder,
                  targetTriple,
                  swiftc,
                  swiftFlags,
                  false,
                  moduleName,
                  sdkPath,
                  swiftModule.getSwiftInterfacePath(),
                  deps.build());
            });
    outputs.addAll(transitiveOutputs);
    outputs.add(rule.getSourcePathToOutput());
  }

  /** A class that represents a Swift module dependency of an Apple SDK. */
  public static class SwiftModule implements Comparable<SwiftModule> {
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
  public static class ClangModule {
    private String name;

    @JsonProperty("modulemap")
    private Path modulemapPath;

    @JsonProperty("clang_deps")
    private List<String> clangDependencies;

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
