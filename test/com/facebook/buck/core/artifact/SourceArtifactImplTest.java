/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.artifact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Runtime;
import java.nio.file.Paths;
import org.junit.Test;

public class SourceArtifactImplTest {
  @Test
  public void skylarkFunctionsWork() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    SourceArtifactImpl artifact =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("foo", "bar.cpp")));

    String expectedShortPath = Paths.get("foo", "bar.cpp").toString();

    assertEquals("bar.cpp", artifact.getBasename());
    assertEquals("cpp", artifact.getExtension());
    assertEquals(Runtime.NONE, artifact.getOwner());
    assertEquals(expectedShortPath, artifact.getShortPath());
    assertTrue(artifact.isSource());
    assertEquals(String.format("<source file '%s'>", expectedShortPath), Printer.repr(artifact));
  }
}
