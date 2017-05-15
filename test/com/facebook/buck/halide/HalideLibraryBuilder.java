/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.halide;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;

public class HalideLibraryBuilder
    extends AbstractNodeBuilder<
        HalideLibraryDescriptionArg.Builder, HalideLibraryDescriptionArg, HalideLibraryDescription,
        BuildRule> {
  public HalideLibraryBuilder(
      BuildTarget target,
      HalideBuckConfig halideBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
        new HalideLibraryDescription(
            CxxPlatformUtils.DEFAULT_CONFIG, defaultCxxPlatform, cxxPlatforms, halideBuckConfig),
        target);
  }

  public HalideLibraryBuilder(BuildTarget target) throws IOException {
    this(
        target,
        createDefaultHalideConfig(new FakeProjectFilesystem()),
        createDefaultPlatform(),
        createDefaultPlatforms());
  }

  public static HalideBuckConfig createDefaultHalideConfig(ProjectFilesystem filesystem)
      throws IOException {
    Path path = Paths.get("fake_compile_script.sh");
    filesystem.touch(path);
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    HalideBuckConfig.HALIDE_SECTION_NAME,
                    ImmutableMap.of(
                        HalideBuckConfig.HALIDE_XCODE_COMPILE_SCRIPT_KEY,
                        path.toString(),
                        "target_platform",
                        "halide-target")))
            .setFilesystem(filesystem)
            .build();
    return new HalideBuckConfig(buckConfig);
  }

  public static CxxPlatform createDefaultPlatform() {
    return CxxPlatform.builder()
        .from(CxxPlatformUtils.DEFAULT_PLATFORM)
        .setFlagMacros(ImmutableMap.of("TEST_MACRO", "test_macro_expansion"))
        .build();
  }

  // The #halide-compiler version of the HalideLibrary rule expects to be able
  // to find a CxxFlavor to use when building for the host architecture.
  // AbstractCxxSourceBuilder doesn't create the default host flavor, so we "override"
  // the createDefaultPlatforms() method here.
  public static FlavorDomain<CxxPlatform> createDefaultPlatforms() {
    Flavor hostFlavor = CxxPlatforms.getHostFlavor();
    CxxPlatform hostCxxPlatform =
        CxxPlatform.builder().from(CxxPlatformUtils.DEFAULT_PLATFORM).setFlavor(hostFlavor).build();

    CxxPlatform defaultCxxPlatform = createDefaultPlatform();

    return new FlavorDomain<>(
        "C/C++ Platform",
        ImmutableMap.<Flavor, CxxPlatform>builder()
            .put(defaultCxxPlatform.getFlavor(), defaultCxxPlatform)
            .put(hostCxxPlatform.getFlavor(), hostCxxPlatform)
            .build());
  }

  public HalideLibraryBuilder setSupportedPlatformsRegex(Pattern supportedPlatformsRegex) {
    getArgForPopulating().setSupportedPlatformsRegex(Optional.of(supportedPlatformsRegex));
    return this;
  }

  public HalideLibraryBuilder setCompilerInvocationFlags(ImmutableList<String> flags) {
    getArgForPopulating().setCompilerInvocationFlags(flags);
    return this;
  }

  public HalideLibraryBuilder setFunctionNameOverride(String functionName) {
    getArgForPopulating().setFunctionName(Optional.of(functionName));
    return this;
  }

  public HalideLibraryBuilder setSrcs(ImmutableSortedSet<SourceWithFlags> srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }

  public HalideLibraryBuilder setHeaders(ImmutableSortedSet<SourcePath> headers) {
    getArgForPopulating().setHeaders(SourceList.ofUnnamedSources(headers));
    return this;
  }

  public HalideLibraryBuilder setHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    getArgForPopulating().setHeaders(SourceList.ofNamedSources(headers));
    return this;
  }

  public HalideLibraryBuilder setCompilerFlags(ImmutableList<String> compilerFlags) {
    getArgForPopulating().setCompilerFlags(compilerFlags);
    return this;
  }

  public HalideLibraryBuilder setLinkerFlags(ImmutableList<StringWithMacros> linkerFlags) {
    getArgForPopulating().setLinkerFlags(linkerFlags);
    return this;
  }

  public HalideLibraryBuilder setFrameworks(ImmutableSortedSet<FrameworkPath> frameworks) {
    getArgForPopulating().setFrameworks(frameworks);
    return this;
  }

  public HalideLibraryBuilder setLibraries(ImmutableSortedSet<FrameworkPath> libraries) {
    getArgForPopulating().setLibraries(libraries);
    return this;
  }

  public HalideLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }
}
