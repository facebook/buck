/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PathSourcePathTest {

  @Test(expected = NullPointerException.class)
  public void relativePathMustBeSet() {
    new PathSourcePath(null);
  }

  @Test
  public void shouldResolveFilesUsingTheBuildContextsFileSystem() {
    PathSourcePath path = new PathSourcePath(Paths.get("cheese"));

    Path resolved = path.resolve();

    assertEquals(Paths.get("cheese"), resolved);
  }

  @Test
  public void shouldReturnTheOriginalPathAsTheReference() {
    Path expected = Paths.get("cheese");
    PathSourcePath path = new PathSourcePath(expected);

    assertEquals(expected, path.asReference());
  }
}
