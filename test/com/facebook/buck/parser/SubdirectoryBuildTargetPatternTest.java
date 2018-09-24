/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.nio.file.Paths;
import org.junit.Test;

public class SubdirectoryBuildTargetPatternTest {

  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Test
  public void testApply() {
    SubdirectoryBuildTargetPattern pattern =
        SubdirectoryBuildTargetPattern.of(
            filesystem.getRootPath(), Paths.get("src/com/facebook/buck/"));

    assertTrue(
        pattern.matches(
            BuildTargetFactory.newInstance(
                filesystem.getRootPath(), "//src/com/facebook/buck:buck")));
    assertTrue(
        pattern.matches(
            BuildTargetFactory.newInstance(
                filesystem.getRootPath(), "//src/com/facebook/buck/bar:bar")));
    assertFalse(
        pattern.matches(
            BuildTargetFactory.newInstance(
                filesystem.getRootPath(), "//src/com/facebook/foo:foo")));
  }
}
