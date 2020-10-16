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

package com.facebook.buck.swift;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import org.junit.Before;
import org.junit.Test;

public class SwiftErrorTransformerTest {
  ProjectFilesystem filesystem;
  SwiftErrorTransformer transformer;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem();
    transformer = new SwiftErrorTransformer(filesystem);
  }

  @Test
  public void testPathIsTransformedToAbsolute() {
    assertEquals(
        transformer.transformLine("relative/path.swift:1:2: error:"),
        String.format("%s:1:2: error:", filesystem.resolve("relative/path.swift")));
  }

  @Test
  public void testColorOutputIsTransformedToAbsolute() {
    assertEquals(
        transformer.transformLine(
            "\u001b[01m\u001b[Krelative/path.swift:1:2: error:\u001b[m\u001b[K"),
        String.format(
            "\u001b[01m\u001b[K%s:1:2: error:\u001b[m\u001b[K",
            filesystem.resolve("relative/path.swift")));
  }

  @Test
  public void testAbsolutePathIsNotTransformed() {
    assertEquals(
        transformer.transformLine("/absolute/path.swift:1:2: error:"),
        "/absolute/path.swift:1:2: error:");
  }

  @Test
  public void testUnrelatedLineIsNotTransformed() {
    assertEquals(transformer.transformLine("not an error"), "not an error");
  }
}
