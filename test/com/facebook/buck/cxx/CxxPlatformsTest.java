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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.google.common.base.Charsets;
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
          .setAspp(new HashedFileTool(Paths.get("borland")))
          .setCc(new HashedFileTool(Paths.get("borland")))
          .setCpp(new HashedFileTool(Paths.get("borland")))
          .setCxx(new HashedFileTool(Paths.get("borland")))
          .setCxxpp(new HashedFileTool(Paths.get("borland")))
          .setCxxld(new HashedFileTool(Paths.get("borland")))
          .setLd(new GnuLinker(new HashedFileTool(Paths.get("borland"))))
          .setAr(new HashedFileTool(Paths.get("borland")))
          .setArExpectedGlobalHeader("something arbitrary".getBytes(Charsets.US_ASCII))
          .setSharedLibraryExtension(".so")
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
}
