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

package com.facebook.buck.jvm.java.tracing;

import static com.facebook.buck.event.TestEventConfigurator.configureTestEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class JavacPhaseEventTest {
  @Test
  public void testEquals() {
    BuildTarget target = BuildTargetFactory.newInstance("//fake:rule");
    JavacPhaseEvent.Phase phase = JavacPhaseEvent.Phase.ENTER;

    JavacPhaseEvent startedEventOne =
        configureTestEvent(JavacPhaseEvent.started(target, phase, ImmutableMap.of()));
    JavacPhaseEvent startedEventTwo =
        configureTestEvent(JavacPhaseEvent.started(target, phase, ImmutableMap.of()));

    assertEquals(startedEventOne, startedEventOne);
    assertNotEquals(startedEventOne, startedEventTwo);
  }

  @Test
  public void testIsRelated() {
    BuildTarget target = BuildTargetFactory.newInstance("//fake:rule");
    JavacPhaseEvent.Phase phase = JavacPhaseEvent.Phase.ANALYZE;

    JavacPhaseEvent.Started startedEventOne =
        configureTestEvent(JavacPhaseEvent.started(target, phase, ImmutableMap.of()));
    JavacPhaseEvent.Started startedEventTwo =
        configureTestEvent(JavacPhaseEvent.started(target, phase, ImmutableMap.of()));
    JavacPhaseEvent finishedEventOne =
        configureTestEvent(JavacPhaseEvent.finished(startedEventOne, ImmutableMap.of()));

    assertTrue(startedEventOne.isRelatedTo(finishedEventOne));
    assertTrue(finishedEventOne.isRelatedTo(startedEventOne));
    assertFalse(startedEventTwo.isRelatedTo(finishedEventOne));
    assertFalse(finishedEventOne.isRelatedTo(startedEventTwo));
  }
}
