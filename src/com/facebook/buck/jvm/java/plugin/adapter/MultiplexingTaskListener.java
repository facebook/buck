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
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Multiplexes messages to several other task listeners. Messages are dispatched to listeners in the
 * order in which the listeners were registered.
 */
class MultiplexingTaskListener implements TaskListener {
  private final CopyOnWriteArraySet<TaskListener> listeners = new CopyOnWriteArraySet<>();

  public void addListener(TaskListener listener) {
    listeners.add(listener);
  }

  public void removeListener(TaskListener listener) {
    listeners.remove(listener);
  }

  @Override
  public void started(TaskEvent e) {
    for (TaskListener listener : listeners) {
      listener.started(e);
    }
  }

  @Override
  public void finished(TaskEvent e) {
    for (TaskListener listener : listeners) {
      listener.finished(e);
    }
  }
}
