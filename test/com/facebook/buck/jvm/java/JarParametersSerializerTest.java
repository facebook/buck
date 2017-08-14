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
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class JarParametersSerializerTest {
  @Test
  public void testSerializingAndDeserializing() throws Exception {
    JarParameters input =
        JarParameters.builder()
            .setJarPath(Paths.get("/some/path"))
            .setRemoveEntryPredicate(
                new RemoveClassesPatternsMatcher(
                    ImmutableSet.of(
                        Pattern.compile("[a-z]"), Pattern.compile("[0-9]", Pattern.MULTILINE))))
            .setEntriesToJar(
                ImmutableSortedSet.of(Paths.get("some/path"), Paths.get("/other path/")))
            .setMainClass(Optional.of("hello I am main class"))
            .setManifestFile(Optional.of(Paths.get("/MANIFEST/FILE.TXT")))
            .setMergeManifests(true)
            .setHashEntries(true)
            .build();

    Map<String, Object> data = JarParametersSerializer.serialize(input);
    JarParameters output = JarParametersSerializer.deserialize(data);

    assertThat(output.getJarPath(), Matchers.equalToObject(input.getJarPath()));
    assertThat(output.getEntriesToJar(), Matchers.equalToObject(input.getEntriesToJar()));
    assertThat(output.getMainClass(), Matchers.equalToObject(input.getMainClass()));
    assertThat(output.getManifestFile(), Matchers.equalToObject(input.getManifestFile()));
    assertThat(
        ((RemoveClassesPatternsMatcher) output.getRemoveEntryPredicate()).getPatterns().size(),
        Matchers.equalToObject(
            ((RemoveClassesPatternsMatcher) input.getRemoveEntryPredicate()).getPatterns().size()));
    for (int i = 0;
        i < ((RemoveClassesPatternsMatcher) input.getRemoveEntryPredicate()).getPatterns().size();
        i++) {
      Pattern inputPattern =
          ((RemoveClassesPatternsMatcher) input.getRemoveEntryPredicate())
              .getPatterns()
              .asList()
              .get(i);
      Pattern outputPattern =
          ((RemoveClassesPatternsMatcher) input.getRemoveEntryPredicate())
              .getPatterns()
              .asList()
              .get(i);
      assertThat(outputPattern.pattern(), Matchers.equalToObject(inputPattern.pattern()));
      assertThat(outputPattern.flags(), Matchers.equalTo(inputPattern.flags()));
    }

    assertTrue(output.getMergeManifests());
    assertTrue(output.getHashEntries());
  }

  @Test
  public void testWorkingWithOptionals() throws Exception {
    JarParameters input =
        JarParameters.builder()
            .setJarPath(Paths.get("/some/path"))
            .setRemoveEntryPredicate(
                new RemoveClassesPatternsMatcher(
                    ImmutableSet.of(
                        Pattern.compile("[a-z]"), Pattern.compile("[0-9]", Pattern.MULTILINE))))
            .setEntriesToJar(
                ImmutableSortedSet.of(Paths.get("some/path"), Paths.get("/other path/")))
            .setMainClass(Optional.empty())
            .setManifestFile(Optional.empty())
            .build();

    Map<String, Object> data = JarParametersSerializer.serialize(input);
    JarParameters output = JarParametersSerializer.deserialize(data);

    assertThat(output.getManifestFile(), Matchers.equalToObject(Optional.empty()));
    assertThat(output.getMainClass(), Matchers.equalToObject(Optional.empty()));
  }
}
