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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.junit.Test;

public class OptionalIntCoercerTest {

  private final Path basePath = MorePaths.EMPTY_PATH;
  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final OptionalIntTypeCoercer coercer = new OptionalIntTypeCoercer();
  private CellPathResolver cellRoots = TestCellBuilder.createCellRoots(filesystem);

  @Test
  public void canCoerceNullToEmptyOptionalInt() throws Exception {
    assertEquals(OptionalInt.empty(), coerce(null));
  }

  @Test
  public void canCoerceIntegerToNonEmptyOptionalInt() throws Exception {
    assertEquals(OptionalInt.of(777), coerce(777));
  }

  private OptionalInt coerce(Object object) throws CoerceFailedException {
    return coercer.coerce(cellRoots, filesystem, basePath, object);
  }
}
