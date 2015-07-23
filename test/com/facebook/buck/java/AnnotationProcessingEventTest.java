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

package com.facebook.buck.java;

import static com.facebook.buck.event.TestEventConfigerator.configureTestEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;

import org.hamcrest.Matchers;
import org.junit.Test;

public class AnnotationProcessingEventTest {

  @Test
  public void testEquals() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//fake:rule");
    BuildTarget targetTwo = BuildTargetFactory.newInstance("//fake:rule2");
    String annotationProcessorName = "com.facebook.FakeProcessor";
    String annotationProcessorName2 = "com.facebook.FakeProcessor2";


    AnnotationProcessingEvent.Started initStartedEventOne = configureTestEvent(
        AnnotationProcessingEvent.started(
            target,
            annotationProcessorName,
            AnnotationProcessingEvent.Operation.INIT,
            0,
            false));
    AnnotationProcessingEvent.Started initStartedEventTwo = configureTestEvent(
        AnnotationProcessingEvent.started(
            target,
            annotationProcessorName,
            AnnotationProcessingEvent.Operation.INIT,
            0,
            false));
    AnnotationProcessingEvent targetTwoInitStartedEvent = configureTestEvent(
        AnnotationProcessingEvent.started(
            targetTwo,
            annotationProcessorName,
            AnnotationProcessingEvent.Operation.INIT,
            0,
            false));
    AnnotationProcessingEvent annotationProcessorTwoInitStartedEvent = configureTestEvent(
        AnnotationProcessingEvent.started(
            target,
            annotationProcessorName2,
            AnnotationProcessingEvent.Operation.INIT,
            0,
            false));
    AnnotationProcessingEvent getSupportedOptionsStartedEvent = configureTestEvent(
        AnnotationProcessingEvent.started(
            target,
            annotationProcessorName,
            AnnotationProcessingEvent.Operation.GET_SUPPORTED_OPTIONS,
            0,
            false));
    AnnotationProcessingEvent finishedInitEventOne = configureTestEvent(
        AnnotationProcessingEvent.finished(initStartedEventOne));
    AnnotationProcessingEvent finishedInitEventTwo = configureTestEvent(
        AnnotationProcessingEvent.finished(initStartedEventTwo));
    assertEquals(initStartedEventOne, initStartedEventOne);
    assertNotEquals(initStartedEventOne, initStartedEventTwo);
    assertNotEquals(initStartedEventOne, targetTwoInitStartedEvent);
    assertNotEquals(initStartedEventOne, annotationProcessorTwoInitStartedEvent);
    assertNotEquals(initStartedEventOne, getSupportedOptionsStartedEvent);
    assertNotEquals(finishedInitEventOne, finishedInitEventTwo);
    assertThat(initStartedEventOne.isRelatedTo(finishedInitEventOne), Matchers.is(true));
    assertThat(initStartedEventOne.isRelatedTo(finishedInitEventTwo), Matchers.is(false));
  }
}
