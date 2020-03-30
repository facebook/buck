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

import com.facebook.buck.jvm.java.plugin.api.BuckJavacTaskListener;
import com.facebook.buck.jvm.java.plugin.api.TaskEventMirror;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

/**
 * Implements {@link BuckJavacTaskListener} by proxying calls to an inner {@link TaskListener}. This
 * is the bridge that allows Buck to call {@link TaskListener}s that are loaded in the compiler's
 * {@link ClassLoader}.
 */
public class TaskListenerProxy implements BuckJavacTaskListener {
  private final TaskListener taskListener;

  public TaskListenerProxy(Object taskListener) {
    if (taskListener instanceof BuckJavacTaskListenerProxy) {
      throw new IllegalArgumentException(
          "taskListener is a proxy, unwrap it rather than creating another proxy");
    }
    this.taskListener = (TaskListener) taskListener;
  }

  public TaskListener getInner() {
    return taskListener;
  }

  @Override
  public void started(TaskEventMirror e) {
    taskListener.started(unmirror(e));
  }

  @Override
  public void finished(TaskEventMirror e) {
    taskListener.finished(unmirror(e));
  }

  private TaskEvent unmirror(TaskEventMirror mirror) {
    return (TaskEvent) mirror.getOriginal();
  }
}
