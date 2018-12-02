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

package com.facebook.buck.parser.cache.json.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.cache.json.BuildFileManifestSerializer;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** This class contains tests that test the deserialization of BuildFileManifest. */
public class BuildFileManifestObjectConverterTest {
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
  public void buildFileManifestDeserializationFromJson() throws Exception {
    BuildFileManifest noEnvFakeManifest = FAKE_MANIFEST.withEnv(ImmutableMap.of());

    // First serialize the manifest.
    byte[] serializedManifest = BuildFileManifestSerializer.serialize(noEnvFakeManifest);

    // Now deserialize and compare the data.
    BuildFileManifest deserializedManifest =
        BuildFileManifestSerializer.deserialize(serializedManifest);

    assertEquals(noEnvFakeManifest, deserializedManifest);
  }

  @Test
  public void buildFileManifestDeserializationFromJsonWhenEnvIsEmpty() throws Exception {
    BuildFileManifest noEnvFakeManifest = FAKE_MANIFEST.withEnv(ImmutableMap.of());

    // First serialize the manifest.
    byte[] serializedManifest = BuildFileManifestSerializer.serialize(noEnvFakeManifest);

    // Now deserialize and compare the data.
    BuildFileManifest deserializedManifest =
        BuildFileManifestSerializer.deserialize(serializedManifest);

    assertEquals(noEnvFakeManifest, deserializedManifest);
  }

  @Test
  public void buildFileManifestDeserializationFromJsonWhenEnvIsNotEmptyThrowsInvalidState()
      throws IOException {
    expectedException.expectMessage(
        "The env field of BuildFileManifest is expected to be always null.");
    expectedException.expect(IllegalStateException.class);
    // First serialize the manifest.
    byte[] serializedManifest = BuildFileManifestSerializer.serialize(FAKE_MANIFEST);

    // Now deserialize and compare the data.
    BuildFileManifestSerializer.deserialize(serializedManifest);
  }

  @Test
  public void buildFileManifestDeserializationFromHardCodedString() throws Exception {

    BuildFileManifest noEnvFakeManifest = FAKE_MANIFEST.withEnv(ImmutableMap.of());

    String manifestJson =
        "{\"targets\" : { \"tar1\":{\"t1K1\" : \"t1V1\",\"t1K2\" : \"t1V2\" }, \"tar2\":{\"t2K1\" : \"t2V1\",\"t2K2\" : \"t2V2\" }},\"includes\" : [ \"/Includes1\", \"/includes2\" ],\"configs\" : {\"confKey1\" : \"confVal1\",\"confKey2\" : \"confVal2\" },\"globManifest\" : [ {\"globSpec\" : {\"include\" : [ \"includeSpec\" ],\"exclude\" : [ \"excludeSpec\" ],\"excludeDirectories\" : true },\"filePaths\" : [ \"FooBar.java\" ] }, {\"globSpec\" : {\"include\" : [ \"includeSpec1\" ],\"exclude\" : [ \"excludeSpec1\" ],\"excludeDirectories\" : false },\"filePaths\" : [ \"BarFoo.java\" ] } ] }";

    // Deserialize and compare the data.
    BuildFileManifest deserializedManifest =
        BuildFileManifestSerializer.deserialize(manifestJson.getBytes(StandardCharsets.UTF_8));

    assertEquals(noEnvFakeManifest, deserializedManifest);
  }
}
