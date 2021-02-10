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

package com.facebook.buck.cxx;

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import org.hamcrest.junit.ExpectedException;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class InferLogLineTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void testFromBuildTargetThrowsWhenPathIsNotAbsolute() {
    Assume.assumeThat(Platform.detect(), not(Platform.WINDOWS));
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Path must be absolute");
    BuildTarget testBuildTarget =
        BuildTargetFactory.newInstance(
            "//target", "short", CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor());

    InferLogLine.fromBuildTarget(testBuildTarget, Paths.get("buck-out/a/b/c/"));
  }

  @Test
  public void testToStringWithCell() {
    Assume.assumeThat(Platform.detect(), not(Platform.WINDOWS));
    BuildTarget testBuildTarget =
        BuildTargetFactory.newInstance("cellname//target:short")
            .withFlavors(
                ImmutableSet.of(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor()));

    String expectedOutput =
        "cellname//target:short#infer-capture-all\t[infer-capture-all]\t/User/user/src/buck-out/a/b/c";
    assertEquals(
        expectedOutput,
        InferLogLine.fromBuildTarget(testBuildTarget, Paths.get("/User/user/src/buck-out/a/b/c/"))
            .toString());
  }

  @Test
  public void testToStringWithoutCell() {
    Assume.assumeThat(Platform.detect(), not(Platform.WINDOWS));
    BuildTarget testBuildTarget =
        BuildTargetFactory.newInstance(
            "//target", "short", CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor());

    String expectedOutput =
        "//target:short#infer-capture-all\t[infer-capture-all]\t/User/user/src/buck-out/a/b/c";
    assertEquals(
        expectedOutput,
        InferLogLine.fromBuildTarget(testBuildTarget, Paths.get("/User/user/src/buck-out/a/b/c/"))
            .toString());
  }
}
