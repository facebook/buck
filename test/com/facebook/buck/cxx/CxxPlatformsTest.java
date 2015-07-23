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

package com.facebook.buck.cxx;

import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Paths;

/**
 * Unit tests for {@link CxxPlatforms}.
 */
public class CxxPlatformsTest {
  @Test
  public void returnsKnownDefaultPlatformSetInConfig() {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of("default_platform", "borland_cxx_452"));
    CxxPlatform borlandCxx452Platform =
      CxxPlatform.builder()
          .setFlavor(ImmutableFlavor.of("borland_cxx_452"))
          .setAs(new HashedFileTool(Paths.get("borland")))
          .setAspp(new DefaultPreprocessor(new HashedFileTool(Paths.get("borland"))))
          .setCc(new DefaultCompiler(new HashedFileTool(Paths.get("borland"))))
          .setCpp(new DefaultPreprocessor(new HashedFileTool(Paths.get("borland"))))
          .setCxx(new DefaultCompiler(new HashedFileTool(Paths.get("borland"))))
          .setCxxpp(new DefaultPreprocessor(new HashedFileTool(Paths.get("borland"))))
          .setLd(new GnuLinker(new HashedFileTool(Paths.get("borland"))))
          .setStrip(new HashedFileTool(Paths.get("borland")))
          .setAr(new GnuArchiver(new HashedFileTool(Paths.get("borland"))))
          .setSharedLibraryExtension(".so")
          .setDebugPathSanitizer(CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER)
          .build();

    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);
    assertThat(
        CxxPlatforms.getConfigDefaultCxxPlatform(
            new CxxBuckConfig(buckConfig),
            ImmutableMap.of(borlandCxx452Platform.getFlavor(), borlandCxx452Platform),
            CxxPlatformUtils.DEFAULT_PLATFORM),
        equalTo(
            borlandCxx452Platform));
  }

  @Test
  public void unknownDefaultPlatformSetInConfigFallsBackToSystemDefault() {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of("default_platform", "borland_cxx_452"));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);
    assertThat(
        CxxPlatforms.getConfigDefaultCxxPlatform(
            new CxxBuckConfig(buckConfig),
            ImmutableMap.<Flavor, CxxPlatform>of(),
            CxxPlatformUtils.DEFAULT_PLATFORM),
        equalTo(
            CxxPlatformUtils.DEFAULT_PLATFORM));
  }

  @Test
  public void combinesPreprocessAndCompileFlagsIsDefault() {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of(
            "cflags", "-Wtest",
            "cxxflags", "-Wexample",
            "cppflags", "-Wp",
            "cxxppflags", "-Wxp"));

    CxxBuckConfig buckConfig =
        new CxxBuckConfig(new FakeBuckConfig(sections));

    CxxPlatform platform = DefaultCxxPlatforms.build(buckConfig);

    assertThat(
        platform.getCflags(),
        hasItem("-Wtest"));
    assertThat(
        platform.getCxxflags(),
        hasItem("-Wexample"));
    assertThat(
        platform.getCppflags(),
        hasItems("-Wtest", "-Wp"));
    assertThat(
        platform.getCxxppflags(),
        hasItems("-Wexample", "-Wxp"));
  }

  @Test
  public void compilerOnlyFlagsNotAddedToPreprocessor() {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of(
            "compiler_only_flags", "-Wtest",
            "cppflags", "-Wp",
            "cxxppflags", "-Wxp"));

    CxxBuckConfig buckConfig =
        new CxxBuckConfig(new FakeBuckConfig(sections));

    CxxPlatform platform = DefaultCxxPlatforms.build(buckConfig);

    assertThat(
        platform.getCflags(),
        hasItem("-Wtest"));
    assertThat(
        platform.getCxxflags(),
        hasItem("-Wtest"));
    assertThat(
        platform.getCppflags(),
        hasItem("-Wp"));
    assertThat(
        platform.getCppflags(),
        not(hasItem("-Wtest")));
    assertThat(
        platform.getCxxppflags(),
        hasItem("-Wxp"));
    assertThat(
        platform.getCxxppflags(),
        not(hasItem("-Wtest")));
  }
}
