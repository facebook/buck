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

import org.easymock.EasyMock;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class FileSourcePathTest {

  @Test(expected = NullPointerException.class)
  public void relativePathMustBeSet() {
    new FileSourcePath(null);
  }

  @Test
  public void shouldResolveFilesUsingTheBuildContextsFileSystem() {
    // Using this as a stub, because I'm too lazy to create all the required deps.
    BuildContext context = EasyMock.createMock(BuildContext.class);

    EasyMock.replay(context);

    FileSourcePath path = new FileSourcePath("cheese");

    Path resolved = path.resolve(context);

    assertEquals(Paths.get("cheese"), resolved);
  }

  @Test
  public void shouldReturnTheOriginalPathAsTheReference() {
    FileSourcePath path = new FileSourcePath("cheese");

    assertEquals("cheese", path.asReference());
  }
}
