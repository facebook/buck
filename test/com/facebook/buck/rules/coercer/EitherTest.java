/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.google.common.base.Function;

import org.junit.Test;

import javax.annotation.Nullable;

public class EitherTest {
  @Test
  public void shouldCorrectlyTransformLeft() {
    BuildTarget expected = BuildTargetFactory.newInstance("//:cake");
    Either<String, Object> either = Either.ofLeft(expected.toString());

    Function<String, BuildTarget> workingTransformer = new Function<String, BuildTarget>() {
      @Override
      public BuildTarget apply(String input) {
        return BuildTargetFactory.newInstance(input);
      }
    };
    Function<Object, BuildTarget> failingTransformer = new Function<Object, BuildTarget>() {
      @Override
      public BuildTarget apply(@Nullable Object input) {
        throw new RuntimeException("Did not expect to be called");
      }
    };

    BuildTarget actual = either.transform(workingTransformer, failingTransformer);

    assertEquals(expected, actual);
  }

  @Test
  public void shouldCorrectlyTransformRight() {
    BuildTarget expected = BuildTargetFactory.newInstance("//:cake");
    Either<Object, String> either = Either.ofRight(expected.toString());

    Function<String, BuildTarget> workingTransformer = new Function<String, BuildTarget>() {
      @Override
      public BuildTarget apply(String input) {
        return BuildTargetFactory.newInstance(input);
      }
    };
    Function<Object, BuildTarget> failingTransformer = new Function<Object, BuildTarget>() {
      @Override
      public BuildTarget apply(@Nullable Object input) {
        throw new RuntimeException("Did not expect to be called");
      }
    };

    BuildTarget actual = either.transform(failingTransformer, workingTransformer);

    assertEquals(expected, actual);
  }
}
