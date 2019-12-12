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

import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import java.util.IdentityHashMap;
import java.util.Map;

public class TestTaskListenerAdapter implements TaskListener {
  public static void addTaskListener(BuckJavacTask task, TestTaskListener listener) {
    task.addTaskListener(getTaskListener(listener));
  }

  public static void removeTaskListener(BuckJavacTask task, TestTaskListener listener) {
    task.removeTaskListener(getTaskListener(listener));
  }

  public static void setTaskListener(BuckJavacTask task, TestTaskListener listener) {
    task.setTaskListener(getTaskListener(listener));
  }

  private static TaskListener getTaskListener(TestTaskListener testTaskListener) {
    if (!taskListeners.containsKey(testTaskListener)) {
      taskListeners.put(testTaskListener, new TestTaskListenerAdapter(testTaskListener));
    }

    return taskListeners.get(testTaskListener);
  }

  private static final Map<TestTaskListener, TaskListener> taskListeners = new IdentityHashMap<>();

  private TestTaskListener listener;

  private TestTaskListenerAdapter(TestTaskListener listener) {
    this.listener = listener;
  }

  @Override
  public void started(TaskEvent e) {
    listener.started(e.getKind().toString());
  }

  @Override
  public void finished(TaskEvent e) {
    listener.finished(e.getKind().toString());
  }
}
