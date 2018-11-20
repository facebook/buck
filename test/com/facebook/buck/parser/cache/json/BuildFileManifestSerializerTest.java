/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.cache.json;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** This class contains tests that exercise the serialization of {@link BuildFileManifest}. */
public class BuildFileManifestSerializerTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private static final BuildFileManifest FAKE_MANIFEST = createFakeManifest();

  @Before
  public void setUp() {
    // JimFS has issues with absolute and relative Windows paths.
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));
  }

  private static BuildFileManifest createFakeManifest() {
    GlobSpec globSpec =
        GlobSpec.builder()
            .setExclude(ImmutableList.of("excludeSpec"))
            .setInclude(ImmutableList.of("includeSpec"))
            .setExcludeDirectories(true)
            .build();
    ImmutableSet<String> globs = ImmutableSet.of("FooBar.java");
    ImmutableList.Builder<GlobSpecWithResult> globSpecBuilder = ImmutableList.builder();
    globSpecBuilder.add(GlobSpecWithResult.of(globSpec, globs));

    globSpec =
        GlobSpec.builder()
            .setExclude(ImmutableList.of("excludeSpec1"))
            .setInclude(ImmutableList.of("includeSpec1"))
            .setExcludeDirectories(false)
            .build();
    globs = ImmutableSet.of("BarFoo.java");
    globSpecBuilder.add(GlobSpecWithResult.of(globSpec, globs));
    ImmutableList<GlobSpecWithResult> globSpecs = globSpecBuilder.build();

    ImmutableMap<String, Optional<String>> envs = ImmutableMap.of("envKey", Optional.of("envVal"));
    ImmutableMap<String, String> configs =
        ImmutableMap.of("confKey1", "confVal1", "confKey2", "confVal2");
    ImmutableSortedSet<String> includes = ImmutableSortedSet.of("/Includes1", "/includes2");
    ImmutableMap<String, Object> target1 = ImmutableMap.of("t1K1", "t1V1", "t1K2", "t1V2");
    ImmutableMap<String, Object> target2 = ImmutableMap.of("t2K1", "t2V1", "t2K2", "t2V2");
    ImmutableMap<String, ImmutableMap<String, Object>> targets =
        ImmutableMap.of("tar1", target1, "tar2", target2);

    return BuildFileManifest.of(targets, includes, configs, Optional.of(envs), globSpecs);
  }

  @Test
  public void buildFileManifestSerializationToJson() throws Exception {

    byte[] serializedManifest = BuildFileManifestSerializer.serialize(FAKE_MANIFEST);
    String resultString =
        new String(serializedManifest, 0, serializedManifest.length, StandardCharsets.UTF_8);

    assertTrue(resultString.contains("includeSpec"));
    assertTrue(resultString.contains("excludeSpec"));
    assertTrue(resultString.contains("FooBar.java"));
    assertTrue(resultString.contains("envVal"));
    assertTrue(resultString.contains("envKey"));
    assertTrue(resultString.contains("t1K1"));
    assertTrue(resultString.contains("t1V1"));
    assertTrue(resultString.contains("t2K1"));
    assertTrue(resultString.contains("t2V1"));
    assertTrue(resultString.contains("confKey1"));
    assertTrue(resultString.contains("confVal1"));
  }

  @Test
  public void buildFileManifestSerializationToJsonWhenEnvIsEmpty() throws Exception {
    BuildFileManifest noEnvFakeManifest = FAKE_MANIFEST.withEnv(ImmutableMap.of());

    byte[] serializedManifest = BuildFileManifestSerializer.serialize(noEnvFakeManifest);
    String resultString =
        new String(serializedManifest, 0, serializedManifest.length, StandardCharsets.UTF_8);

    assertTrue(resultString.contains("includeSpec"));
    assertTrue(resultString.contains("excludeSpec"));
    assertTrue(resultString.contains("FooBar.java"));
    assertTrue(resultString.contains("t1K1"));
    assertTrue(resultString.contains("t1V1"));
    assertTrue(resultString.contains("t2K1"));
    assertTrue(resultString.contains("t2V1"));
    assertTrue(resultString.contains("confKey1"));
    assertTrue(resultString.contains("confVal1"));
  }
}
