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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CommandLineBuildTargetNormalizerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectFilesystem filesystem;
  private Cells cells;
  private BuckConfig config;

  @Before
  public void setUp() {
    setUp(true);
  }

  private void setUp(boolean resolveRelativeTargets) {
    ImmutableMap<String, ImmutableMap<String, String>> rawConfig =
        ImmutableMap.of(
            "ui",
            ImmutableMap.of(
                "relativize_targets_to_working_directory",
                resolveRelativeTargets ? "true" : "false"));

    config = FakeBuckConfig.builder().setSections(rawConfig).build();
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot(), config.getConfig());
    cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
  }

  @Test
  public void testNormalize() {
    assertEquals("//src/com/facebook/orca:orca", normalize("src/com/facebook/orca"));
    assertEquals("//src/com/facebook/orca:orca", normalize("//src/com/facebook/orca"));
    assertEquals("//src/com/facebook/orca:orca", normalize("src/com/facebook/orca:orca"));
    assertEquals("//src/com/facebook/orca:orca", normalize("//src/com/facebook/orca:orca"));

    assertEquals("//src/com/facebook/orca:orca", normalize("src/com/facebook/orca/"));
    assertEquals("//src/com/facebook/orca:orca", normalize("//src/com/facebook/orca/"));

    assertEquals("//src/com/facebook/orca:messenger", normalize("src/com/facebook/orca:messenger"));
    assertEquals(
        "//src/com/facebook/orca:messenger", normalize("//src/com/facebook/orca:messenger"));

    // Because tab-completing a directory name often includes the trailing slash, we want to support
    // the user tab-completing, then typing the colon, followed by the short name.
    assertEquals(
        "Slash before colon should be stripped",
        "//src/com/facebook/orca:messenger",
        normalize("src/com/facebook/orca/:messenger"));

    // Assert the cell prefix normalizes
    assertEquals("other//src/com/facebook/orca:orca", normalize("other//src/com/facebook/orca"));
    assertEquals(
        "@other//src/com/facebook/orca:messenger",
        normalize("@other//src/com/facebook/orca:messenger"));
  }

  @Test
  public void testNormalizesRelativePathsWhenEnabled() {
    Path subdir = Paths.get("subdir");

    assertEquals("//foo/bar:baz", normalize("//foo/bar:baz"));
    assertEquals("//foo/bar:", normalize("//foo/bar:"));
    assertEquals("//foo/bar:bar", normalize("//foo/bar"));
    assertEquals("//foo:bar", normalize("//foo:bar"));
    assertEquals("//foo:", normalize("//foo:"));
    assertEquals("//foo:foo", normalize("//foo"));
    assertEquals("//:baz", normalize("//:baz"));
    assertEquals("//:", normalize("//:"));

    assertEquals("//subdir/foo/bar:baz", normalize(subdir, "foo/bar:baz"));
    assertEquals("//subdir/foo/bar:", normalize(subdir, "foo/bar:"));
    assertEquals("//subdir/foo/bar:bar", normalize(subdir, "foo/bar"));
    assertEquals("//subdir/foo:bar", normalize(subdir, "foo:bar"));
    assertEquals("//subdir/foo:", normalize(subdir, "foo:"));
    assertEquals("//subdir/foo:foo", normalize(subdir, "foo"));
    assertEquals("//subdir:baz", normalize(subdir, ":baz"));
    assertEquals("//subdir:", normalize(subdir, ":"));
  }

  @Test
  public void testNormalizesRelativePathsWhenDisabled() {
    setUp(false);
    Path subdir = Paths.get("subdir");

    assertEquals("//foo/bar:baz", normalize("//foo/bar:baz"));
    assertEquals("//foo/bar:", normalize("//foo/bar:"));
    assertEquals("//foo/bar:bar", normalize("//foo/bar"));
    assertEquals("//foo:bar", normalize("//foo:bar"));
    assertEquals("//foo:", normalize("//foo:"));
    assertEquals("//foo:foo", normalize("//foo"));
    assertEquals("//:baz", normalize("//:baz"));
    assertEquals("//:", normalize("//:"));

    assertEquals("//foo/bar:baz", normalize(subdir, "foo/bar:baz"));
    assertEquals("//foo/bar:", normalize(subdir, "foo/bar:"));
    assertEquals("//foo/bar:bar", normalize(subdir, "foo/bar"));
    assertEquals("//foo:bar", normalize(subdir, "foo:bar"));
    assertEquals("//foo:", normalize(subdir, "foo:"));
    assertEquals("//foo:foo", normalize(subdir, "foo"));
    assertEquals("//:baz", normalize(subdir, ":baz"));
    assertEquals("//:", normalize(subdir, ":"));
  }

  @Test(expected = NullPointerException.class)
  public void testNormalizeThrows() {
    normalize(null);
  }

  @Test
  public void testNormalizeTargetsAtRoot() {
    assertEquals("//:wakizashi", normalize("//:wakizashi"));
    assertEquals("//:wakizashi", normalize(":wakizashi"));
  }

  private String normalize(String buildTargetFromCommandLine) {
    return normalize(Paths.get(""), buildTargetFromCommandLine);
  }

  private String normalize(Path relativeWorkingDirectory, String buildTargetFromCommandLine) {
    return new CommandLineBuildTargetNormalizer(
            cells.getRootCell(),
            cells.getRootCell().getRoot().resolve(relativeWorkingDirectory).getPath(),
            config)
        .normalizeBuildTargetIdentifier(buildTargetFromCommandLine);
  }
}
