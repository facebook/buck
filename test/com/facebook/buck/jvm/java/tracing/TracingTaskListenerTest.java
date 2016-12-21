/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.easymock.EasyMock.createMock;

import com.facebook.buck.testutil.CompilerTreeApiTestRunner;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

import org.easymock.EasyMock;
import org.easymock.IMocksControl;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.tools.JavaFileObject;

@RunWith(CompilerTreeApiTestRunner.class)
public class TracingTaskListenerTest {
  private IMocksControl mockControl;
  private JavacPhaseTracer mockTracer;
  private TaskListener mockNextListener;
  private TracingTaskListener tracingTaskListener;

  @Before
  public void setUp() {
    mockControl = EasyMock.createStrictControl();
    mockTracer = mockControl.createMock(JavacPhaseTracer.class);
    mockNextListener = mockControl.createMock(TaskListener.class);

    tracingTaskListener = new TracingTaskListener(mockTracer, mockNextListener);
  }

  /**
   * In order for TracingTaskListener to record the most accurate timings for time spent in javac,
   * it needs to trace start events after chaining to the next listener.
   */
  @Test
  public void testTracesAfterChainingOnStart() {
    TaskEvent enterEvent = new TaskEvent(TaskEvent.Kind.ENTER, createMock(JavaFileObject.class));
    mockControl.checkOrder(true);
    mockNextListener.started(enterEvent);
    mockTracer.beginEnter();
    mockControl.replay();

    tracingTaskListener.started(enterEvent);
    mockControl.verify();
  }

  /**
   * In order for TracingTaskListener to record the most accurate timings for time spent in javac,
   * it needs to trace finish events before it chains to the next listener.
   */
  @Test
  public void testTracesBeforeChainingOnFinish() {
    TaskEvent parseEvent = new TaskEvent(TaskEvent.Kind.PARSE, createMock(JavaFileObject.class));

    mockControl.checkOrder(true);
    mockTracer.endParse();
    mockNextListener.finished(parseEvent);
    mockControl.replay();

    tracingTaskListener.finished(parseEvent);
    mockControl.verify();
  }
}
