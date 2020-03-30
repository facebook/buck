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

package com.facebook.buck.jvm.java.plugin.adapter;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import org.easymock.EasyMock;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class TaskListenerWrapperTest {
  private TaskListener innerListener = EasyMock.createMock(TaskListener.class);

  @Test
  public void testChainsToInnerOnStart() {
    TaskListenerWrapper wrapper = new TaskListenerWrapper(innerListener);
    TaskEvent e = new TaskEvent(TaskEvent.Kind.PARSE);

    innerListener.started(e);

    EasyMock.replay(innerListener);

    wrapper.started(e);

    EasyMock.verify(innerListener);
  }

  @Test
  public void testChainsToInnerOnFinish() {
    TaskListenerWrapper wrapper = new TaskListenerWrapper(innerListener);
    TaskEvent e = new TaskEvent(TaskEvent.Kind.PARSE);

    innerListener.finished(e);

    EasyMock.replay(innerListener);

    wrapper.finished(e);

    EasyMock.verify(innerListener);
  }

  @Test
  public void testIgnoresNullListeners() {
    TaskListenerWrapper wrapper = new TaskListenerWrapper(null);
    TaskEvent e = new TaskEvent(TaskEvent.Kind.PARSE);

    wrapper.started(e);
    wrapper.finished(e);

    // Expect no crashes
  }
}
