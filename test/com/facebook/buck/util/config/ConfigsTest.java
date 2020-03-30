/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.util.config;

import static org.junit.Assert.*;

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;

public class ConfigsTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void configCreationGeneratesConfigMap() throws IOException {

    Path root = tmp.getRoot();

    Path buckConfig = tmp.newFile(".buckconfig");
    MostFiles.writeLinesToFile(
        ImmutableList.of("[test_section]", "test_field = value2"), buckConfig);

    Path buckConfigLocal = tmp.newFile(".buckconfig.local");
    MostFiles.writeLinesToFile(
        ImmutableList.of("[test_section]", "test_field = value1"), buckConfigLocal);

    Config config = Configs.createDefaultConfig(root, RawConfig.of(ImmutableMap.of()));

    ImmutableSet<Path> addedPaths =
        Stream.of(".buckconfig", ".buckconfig.local")
            .map(root::resolve)
            .collect(ImmutableSet.toImmutableSet());

    /** not using assertEqual because more configs can be added by {@code createDefaultConfig} */
    assertTrue(config.getConfigsMap().keySet().containsAll(addedPaths));
  }

  @Test
  public void configOverrideOtherSources() throws IOException {

    Path buckConfig = tmp.newFile(".buckconfig");
    MostFiles.writeLinesToFile(
        ImmutableList.of("[test_section]", "test_field = value2"), buckConfig);

    Path buckConfigLocal = tmp.newFile(".buckconfig.local");
    MostFiles.writeLinesToFile(
        ImmutableList.of("[test_section]", "test_field = value1"), buckConfigLocal);

    RawConfig configOtherSources =
        RawConfig.of(ImmutableMap.of("test_section", ImmutableMap.of("test_field", "value0")));

    Config config = Configs.createDefaultConfig(tmp.getRoot(), configOtherSources);

    assertEquals("value0", config.get("test_section", "test_field").get());
  }

  @Test
  public void configOverrideFilePriority() throws IOException {

    // ordered in increasing priority (top is least important, bottom is the most)
    // only tests files that could be inside the project's directory.

    Path root = tmp.getRoot();

    Path buckConfig = tmp.newFile(".buckconfig");
    MostFiles.writeLinesToFile(
        ImmutableList.of("[test_section]", "test_field = value2"), buckConfig);

    Config config = Configs.createDefaultConfig(root, RawConfig.of(ImmutableMap.of()));

    assertEquals("value2", config.get("test_section", "test_field").get());

    Path buckConfigLocal = tmp.newFile(".buckconfig.local");
    MostFiles.writeLinesToFile(
        ImmutableList.of("[test_section]", "test_field = value1"), buckConfigLocal);

    config = Configs.createDefaultConfig(root, RawConfig.of(ImmutableMap.of()));
    assertEquals("value1", config.get("test_section", "test_field").get());
  }
}
