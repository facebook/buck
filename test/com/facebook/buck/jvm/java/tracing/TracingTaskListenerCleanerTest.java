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

package com.facebook.buck.jvm.java.tracing;

import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.google.common.collect.ImmutableList;
import org.easymock.EasyMockRunner;
import org.easymock.Mock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EasyMockRunner.class)
public class TracingTaskListenerCleanerTest {

  @Mock private JavacPhaseTracer mockTracer;
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
  public void testAnalyzeEventsCombinedIntoOne() {
    mockTracer.beginAnalyze();
    mockTracer.endAnalyze(
        ImmutableList.of("file1", "file2", "file3"), ImmutableList.of("type1", "type2", "type3"));

    replay(mockTracer);

    traceCleaner.startAnalyze("file1", "type1");
    traceCleaner.startAnalyze("file2", "type2");
    traceCleaner.startAnalyze("file3", "type3");
    traceCleaner.finishAnalyze("file1", "type1");
    traceCleaner.finishAnalyze("file2", "type2");
    traceCleaner.finishAnalyze("file3", "type3");

    verify(mockTracer);
  }

  @Test
  public void testUnmatchedAnalyzeFinishAddsAStarted() {
    mockTracer.beginAnalyze();
    mockTracer.endAnalyze(ImmutableList.of("file"), ImmutableList.of("type"));

    replay(mockTracer);

    traceCleaner.finishAnalyze("file", "type");

    verify(mockTracer);
  }

  @Test
  public void testUnmatchedAnalyzeFinishAfterMatchTracesTwoAnalyzeEvents() {
    mockTracer.beginAnalyze();
    mockTracer.endAnalyze(ImmutableList.of("file1"), ImmutableList.of("type1"));
    mockTracer.beginAnalyze();
    mockTracer.endAnalyze(ImmutableList.of("file2"), ImmutableList.of("type2"));

    replay(mockTracer);

    traceCleaner.startAnalyze("file1", "type1");
    traceCleaner.finishAnalyze("file1", "type1");
    traceCleaner.finishAnalyze("file2", "type2");

    verify(mockTracer);
  }
}
