/*
 * Copyright 2014-present Facebook, Inc.
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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.Test;

public class EitherTypeCoercerTest {

  private final Path basePath = Paths.get("java/com/facebook/buck/example");
  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final StringTypeCoercer id = new StringTypeCoercer();

  @Test
  public void canCoercePairWrappedInEither() throws CoerceFailedException {
    EitherTypeCoercer<Pair<String, String>, String> coercer =
        new EitherTypeCoercer<>(new PairTypeCoercer<>(id, id), id);

    Either<Pair<String, String>, String> seen =
        coercer.coerce(
            createCellRoots(filesystem), filesystem, basePath, Arrays.asList("abc", "de"));

    assertEquals(Either.ofLeft(new Pair<>("abc", "de")), seen);
  }
}
