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
import com.facebook.buck.cxx.AbstractCxxSourceBuilder;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxPreprocessMode;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class HalideLibraryBuilder extends
    AbstractCxxSourceBuilder<HalideLibraryDescription.Arg, HalideLibraryBuilder> {
  public HalideLibraryBuilder(
    BuildTarget target,
    HalideBuckConfig halideBuckConfig,
    FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
      new HalideLibraryDescription(
        cxxPlatforms,
        CxxPreprocessMode.SEPARATE,
        halideBuckConfig),
      target);
  }

  public HalideLibraryBuilder(BuildTarget target) throws IOException {
    this(
      target,
      createDefaultHalideConfig(new FakeProjectFilesystem()),
      createDefaultPlatforms());
  }

  public static HalideBuckConfig createDefaultHalideConfig(
      ProjectFilesystem filesystem) throws IOException {
    Path path = Paths.get("fake_compile_script.sh");
    filesystem.touch(path);
    BuckConfig buckConfig = FakeBuckConfig.builder()
      .setSections(
        ImmutableMap.of(
          HalideBuckConfig.HALIDE_SECTION_NAME,
          ImmutableMap.of(
            HalideBuckConfig.HALIDE_XCODE_COMPILE_SCRIPT_KEY,
            path.toString())))
      .setFilesystem(filesystem)
      .build();
    return new HalideBuckConfig(buckConfig);
  }

  // The #halide-compiler version of the HalideLibrary rule expects to be able
  // to find a CxxFlavor called "default", which we assume is the flavor to use
  // when building for the host architecture. AbstractCxxBuilder doesn't create
  // the "default" flavor, so we "override" the createDefaultPlatforms() method
  // here.
  public static FlavorDomain<CxxPlatform> createDefaultPlatforms() {
    Flavor defaultFlavor = ImmutableFlavor.of("default");
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;
    return new FlavorDomain<>(
        "C/C++ Platform",
        ImmutableMap.<Flavor, CxxPlatform>builder()
          .put(defaultFlavor, cxxPlatform)
          .put(cxxPlatform.getFlavor(), cxxPlatform)
          .build());
  }

  @Override
  protected HalideLibraryBuilder getThis() {
    return this;
  }

}
