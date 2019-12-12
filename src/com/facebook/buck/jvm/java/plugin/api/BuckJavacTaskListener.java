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

package com.facebook.buck.jvm.java.plugin.api;

/**
 * Listens to events coming from the Java compiler. This is analogous to {@link
 * com.sun.source.util.TaskListener}, but loads in Buck's {@link ClassLoader} instead of the
 * compiler's.
 */
public interface BuckJavacTaskListener {
  static BuckJavacTaskListener wrapRealTaskListener(PluginClassLoader loader, Object taskListener) {
    Class<? extends BuckJavacTaskListener> taskListenerProxyClass =
        loader.loadClass(
            "com.facebook.buck.jvm.java.plugin.adapter.TaskListenerProxy",
            BuckJavacTaskListener.class);

    try {
      return taskListenerProxyClass.getConstructor(Object.class).newInstance(taskListener);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  void started(TaskEventMirror e);

  void finished(TaskEventMirror e);
}
