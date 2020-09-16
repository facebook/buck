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

package com.facebook.buck.intellij.ideabuck.visibility;

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class BuckVisibilityStateTest {
  @Test
  public void unknownTest() {
    BuckVisibilityState buckVisibilityState =
        new BuckVisibilityState(BuckVisibilityState.VisibleState.UNKNOWN);
    BuckTarget buckTarget = BuckTarget.parse("").orElse(null);

    Assert.assertEquals(
        buckVisibilityState.getVisibility(buckTarget), BuckVisibilityState.VisibleState.UNKNOWN);
  }

  @Test
  public void listTest() {
    BuckTarget bucktarget = BuckTarget.parse("//foo/bar:bar").orElse(null);
    List<BuckTargetPattern> visibilities = Arrays.asList(bucktarget.asPattern());
    BuckVisibilityState buckVisibilityState = new BuckVisibilityState(visibilities);

    Assert.assertEquals(
        buckVisibilityState.getVisibility(bucktarget), BuckVisibilityState.VisibleState.VISIBLE);
  }
}
