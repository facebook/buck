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

package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class TestIncludeExcludeTest {

  @Test
  public void testNoIncludeExclude() {
    assertEquals(
        filter(
            ImmutableSet.of(BuildTargetFactory.newInstance("//a/b:file1")),
            ImmutableSet.of(),
            ImmutableSet.of()),
        ImmutableSet.of(BuildTargetFactory.newInstance("//a/b:file1")));
  }

  @Test
  public void testInclude() {
    assertEquals(
        filter(
            ImmutableSet.of(
                BuildTargetFactory.newInstance("//a/b:file1"),
                BuildTargetFactory.newInstance("//b/c:file1")),
            ImmutableSet.of("//a/..."),
            ImmutableSet.of()),
        ImmutableSet.of(BuildTargetFactory.newInstance("//a/b:file1")));
  }

  @Test
  public void testExclude() {
    assertEquals(
        filter(
            ImmutableSet.of(
                BuildTargetFactory.newInstance("//a/b:file1"),
                BuildTargetFactory.newInstance("//b/c:file1")),
            ImmutableSet.of(),
            ImmutableSet.of("//a/...")),
        ImmutableSet.of(BuildTargetFactory.newInstance("//b/c:file1")));
  }

  @Test
  public void testIncludeExclude() {
    assertEquals(
        filter(
            ImmutableSet.of(
                BuildTargetFactory.newInstance("//a/b:file1"),
                BuildTargetFactory.newInstance("//a/c:file1"),
                BuildTargetFactory.newInstance("//b/c:file1")),
            ImmutableSet.of("//a/..."),
            ImmutableSet.of("//a/b/...")),
        ImmutableSet.of(BuildTargetFactory.newInstance("//a/c:file1")));
  }

  private ImmutableSet<BuildTarget> filter(
      ImmutableSet<BuildTarget> targets,
      ImmutableSet<String> includes,
      ImmutableSet<String> excludes) {
    CellPathResolver cellPathResolver = TestCellPathResolver.get(new FakeProjectFilesystem());
    return IjProjectCommandHelper.filterTests(targets, cellPathResolver, includes, excludes);
  }
}
