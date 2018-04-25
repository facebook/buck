/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.test.external;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import org.junit.Test;

public class ExternalTestSpecCalculationEventTest {
  @Test
  public void startAndStopShouldRelateProperlyBasedOnHash() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//example:target");
    ExternalTestSpecCalculationEvent.Started started =
        ExternalTestSpecCalculationEvent.started(buildTarget);
    ExternalTestSpecCalculationEvent.Finished finished =
        ExternalTestSpecCalculationEvent.finished(buildTarget);

    assertTrue(started.isRelatedTo(finished));
    assertTrue(finished.isRelatedTo(started));
  }

  @Test
  public void shouldNotBelieveThatEventsThatAreNotRelatedAreRelated() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//example:target");
    BuildTarget otherBuildTarget = BuildTargetFactory.newInstance("//example:other-target");

    ExternalTestSpecCalculationEvent.Started started =
        ExternalTestSpecCalculationEvent.started(buildTarget);
    ExternalTestSpecCalculationEvent.Finished finished =
        ExternalTestSpecCalculationEvent.finished(otherBuildTarget);

    assertFalse(started.isRelatedTo(finished));
    assertFalse(finished.isRelatedTo(started));
  }
}
