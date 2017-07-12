/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class DirectToJarOutputSettingsSerializerTest {
  @Test
  public void testSerializingAndDeserializing() throws Exception {
    DirectToJarOutputSettings input =
        DirectToJarOutputSettings.of(
            Paths.get("/some/path"),
            ImmutableSet.of(Pattern.compile("[a-z]"), Pattern.compile("[0-9]", Pattern.MULTILINE)),
            ImmutableSortedSet.of(Paths.get("some/path"), Paths.get("/other path/")),
            Optional.of("hello I am main class"),
            Optional.of(Paths.get("/MANIFEST/FILE.TXT")));

    Map<String, Object> data = DirectToJarOutputSettingsSerializer.serialize(input);
    DirectToJarOutputSettings output = DirectToJarOutputSettingsSerializer.deserialize(data);

    assertThat(
        output.getDirectToJarOutputPath(),
        Matchers.equalToObject(input.getDirectToJarOutputPath()));
    assertThat(output.getEntriesToJar(), Matchers.equalToObject(input.getEntriesToJar()));
    assertThat(output.getMainClass(), Matchers.equalToObject(input.getMainClass()));
    assertThat(output.getManifestFile(), Matchers.equalToObject(input.getManifestFile()));
    assertThat(
        output.getClassesToRemoveFromJar().size(),
        Matchers.equalToObject(input.getClassesToRemoveFromJar().size()));
    for (int i = 0; i < input.getClassesToRemoveFromJar().size(); i++) {
      Pattern inputPattern = input.getClassesToRemoveFromJar().asList().get(i);
      Pattern outputPattern = output.getClassesToRemoveFromJar().asList().get(i);
      assertThat(outputPattern.pattern(), Matchers.equalToObject(inputPattern.pattern()));
      assertThat(outputPattern.flags(), Matchers.equalTo(inputPattern.flags()));
    }
  }

  @Test
  public void testWorkingWithOptionals() throws Exception {
    DirectToJarOutputSettings input =
        DirectToJarOutputSettings.of(
            Paths.get("/some/path"),
            ImmutableSet.of(Pattern.compile("[a-z]"), Pattern.compile("[0-9]", Pattern.MULTILINE)),
            ImmutableSortedSet.of(Paths.get("some/path"), Paths.get("/other path/")),
            Optional.empty(),
            Optional.empty());

    Map<String, Object> data = DirectToJarOutputSettingsSerializer.serialize(input);
    DirectToJarOutputSettings output = DirectToJarOutputSettingsSerializer.deserialize(data);

    assertThat(output.getManifestFile(), Matchers.equalToObject(Optional.empty()));
    assertThat(output.getMainClass(), Matchers.equalToObject(Optional.empty()));
  }
}
