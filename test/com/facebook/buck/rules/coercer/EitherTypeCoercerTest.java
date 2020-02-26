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

package com.facebook.buck.rules.coercer;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import java.util.Arrays;
import org.junit.Test;

public class EitherTypeCoercerTest {

  private final ForwardRelativePath basePath =
      ForwardRelativePath.of("java/com/facebook/buck/example");
  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final StringTypeCoercer id = new StringTypeCoercer();

  @Test
  public void canCoercePairWrappedInEither() throws CoerceFailedException {
    EitherTypeCoercer<Pair<String, String>, String, Pair<String, String>, String> coercer =
        new EitherTypeCoercer<>(new PairTypeCoercer<>(id, id), id);

    Either<Pair<String, String>, String> seen =
        coercer.coerceBoth(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            Arrays.asList("abc", "de"));

    assertEquals(Either.ofLeft(new Pair<>("abc", "de")), seen);
  }

  @Test
  public void coerceToConfiguredIsIdentity() throws Exception {
    {
      Either<String, UnconfiguredBuildTarget> input = Either.ofLeft("a");
      Either<String, BuildTarget> coerced =
          new EitherTypeCoercer<>(
                  new StringTypeCoercer(),
                  new BuildTargetTypeCoercer(
                      new UnconfiguredBuildTargetTypeCoercer(
                          new ParsingUnconfiguredBuildTargetViewFactory())))
              .coerce(
                  createCellRoots(filesystem).getCellNameResolver(),
                  filesystem,
                  basePath,
                  UnconfiguredTargetConfiguration.INSTANCE,
                  UnconfiguredTargetConfiguration.INSTANCE,
                  input);
      assertSame(input, coerced);
    }
    {
      Either<UnconfiguredBuildTarget, String> input = Either.ofRight("a");
      Either<BuildTarget, String> coerced =
          new EitherTypeCoercer<>(
                  new BuildTargetTypeCoercer(
                      new UnconfiguredBuildTargetTypeCoercer(
                          new ParsingUnconfiguredBuildTargetViewFactory())),
                  new StringTypeCoercer())
              .coerce(
                  createCellRoots(filesystem).getCellNameResolver(),
                  filesystem,
                  basePath,
                  UnconfiguredTargetConfiguration.INSTANCE,
                  UnconfiguredTargetConfiguration.INSTANCE,
                  input);
      assertSame(input, coerced);
    }
  }
}
