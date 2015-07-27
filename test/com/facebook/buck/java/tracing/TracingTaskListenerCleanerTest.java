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

package com.facebook.buck.java.tracing;

import com.google.common.collect.ImmutableList;

import org.easymock.EasyMockRunner;
import org.easymock.Mock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

@RunWith(EasyMockRunner.class)
public class TracingTaskListenerCleanerTest {

  @Mock
  private JavacPhaseTracer mockTracer;
  private TracingTaskListener.TraceCleaner traceCleaner;

  @Before
  public void setUp() {
    traceCleaner = new TracingTaskListener.TraceCleaner(mockTracer);
  }

  @Test
  public void testEnterEventsCombinedIntoOne() {
    mockTracer.beginEnter();
    mockTracer.endEnter(ImmutableList.of("fileOne", "fileTwo", "fileThree"));

    replay(mockTracer);

    traceCleaner.startEnter("fileOne");
    traceCleaner.startEnter("fileTwo");
    traceCleaner.startEnter("fileThree");
    traceCleaner.finishEnter();
    traceCleaner.finishEnter();
    traceCleaner.finishEnter();

    verify(mockTracer);
  }

  @Test
  public void testUnmatchedAnalyzeFinishAddsAStarted() {
    mockTracer.beginAnalyze("file", "type");
    mockTracer.endAnalyze();

    replay(mockTracer);

    traceCleaner.finishAnalyze("file", "type");

    verify(mockTracer);
  }

  @Test
  public void testUnmatchedAnalyzeFinishAfterMatchTracesTwoAnalyzeEvents() {
    mockTracer.beginAnalyze("file1", "type1");
    mockTracer.endAnalyze();
    mockTracer.beginAnalyze("file2", "type2");
    mockTracer.endAnalyze();

    replay(mockTracer);

    traceCleaner.startAnalyze("file1", "type1");
    traceCleaner.finishAnalyze("file1", "type1");
    traceCleaner.finishAnalyze("file2", "type2");

    verify(mockTracer);
  }
}
