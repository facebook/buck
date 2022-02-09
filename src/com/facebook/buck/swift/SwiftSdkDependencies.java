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
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.swift.toolchain.SwiftSdkDependenciesProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

/**
 * Parser for Swift SDK dependency info JSON. The expected input can be generated using the script
 * in `scripts/generate_swift_sdk_dependencies.py`.
 */
public class SwiftSdkDependencies implements SwiftSdkDependenciesProvider {

  private final SdkDependencyJson sdkDependencies;

  private final ImmutableMap<String, SwiftModule> swiftModules;

  private final ImmutableMap<String, ImmutableList<SwiftModule>> swiftDependencies;

  @SuppressWarnings("unused")
  private final Flavor platformFlavor;

  public SwiftSdkDependencies(
      String sdkDependenciesPath, Tool swiftc, AppleCompilerTargetTriple triple)
      throws HumanReadableException {
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      sdkDependencies =
          objectMapper.readValue(Paths.get(sdkDependenciesPath).toFile(), SdkDependencyJson.class);
    } catch (IOException ex) {
      throw new HumanReadableException(
          "Failed to parse SDK dependencies info: " + ex.getLocalizedMessage());
    }

    // Pre-calculate the SDK compilation flavor to use for this compiler and platform. We use the
    // unversioned triple as it should only be the architecture that contributes to the output.
    int hashCode =
        sdkDependencies.getSdkVersion().hashCode()
            ^ swiftc.hashCode()
            ^ triple.getUnversionedTriple().hashCode();
    platformFlavor = InternalFlavor.of(Integer.toHexString(hashCode));

    ImmutableMap.Builder<String, SwiftModule> modulesBuilder = ImmutableMap.builder();
    for (SwiftModule module : sdkDependencies.getSwiftDependencies()) {
      modulesBuilder.put(module.getName(), module);
    }
    swiftModules = modulesBuilder.build();

    ImmutableMap.Builder<String, ImmutableList<SwiftModule>> depsBuilder = ImmutableMap.builder();
    for (SwiftModule module : sdkDependencies.getSwiftDependencies()) {
      ImmutableList.Builder<SwiftModule> moduleDepsBuilder = ImmutableList.builder();
      for (String dep : module.getSwiftDependencies()) {
        moduleDepsBuilder.add(swiftModules.get(dep));
      }
      depsBuilder.put(module.getName(), moduleDepsBuilder.build());
    }
    swiftDependencies = depsBuilder.build();
  }

  public ImmutableList<SwiftModule> getSwiftDependencies(String moduleName) {
    return swiftDependencies.get(moduleName);
  }

  public SwiftModule getSwiftModule(String moduleName) {
    return swiftModules.get(moduleName);
  }

  /** A class that represents a Swift module dependency of an Apple SDK. */
  public static class SwiftModule implements Comparable<SwiftModule> {
    private String name;

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
