/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.plugin.adapter;

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.lang.model.element.TypeElement;

/**
 * Extends {@link JavacTask} with functionality that is useful for Buck:
 *
 * <ul>
 *   <li>Exposes the enter method from JavacTaskImpl
 *   <li>Pre-javac-8 support for addTaskListener/removeTaskListener
 * </ul>
 */
public class BuckJavacTask extends JavacTaskWrapper {
  private final Map<BuckJavacPlugin, String[]> pluginsAndArgs = new LinkedHashMap<>();
  private final MultiplexingTaskListener taskListeners = new MultiplexingTaskListener();
  private final PostEnterTaskListener postEnterTaskListener;
  private final List<Consumer<Set<TypeElement>>> postEnterCallbacks = new ArrayList<>();

  private boolean pluginsInstalled = false;

  @Nullable private TaskListener singleTaskListener;

  public BuckJavacTask(JavacTask inner) {
    super(inner);
    if (inner instanceof BuckJavacTask) {
      throw new IllegalArgumentException();
    }

    inner.setTaskListener(
        new TaskListener() {
          @Override
          public void started(TaskEvent e) {
            BuckJavacTask.this.started(e);
          }

          @Override
          public void finished(TaskEvent e) {
            BuckJavacTask.this.finished(e);
          }
        });

    postEnterTaskListener = new PostEnterTaskListener(this, this::onPostEnter);
  }

  public Iterable<? extends TypeElement> enter() throws IOException {
    try {
      @SuppressWarnings("unchecked")
      Iterable<? extends TypeElement> result =
          (Iterable<? extends TypeElement>) inner.getClass().getMethod("enter").invoke(inner);
      return result;
    } catch (IllegalAccessException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }

      throw new RuntimeException(e);
    }
  }

  /**
   * Sets a {@link TaskListener}. Like {@link JavacTask}'s implementation of this method, the
   * listener does not replace listeners added with {@link #addTaskListener(TaskListener)}. Instead,
   * it replaces only the listener provided in the previous call to this method, if any.
   *
   * <p>Presumably this behavior was to enable {@link com.sun.source.util.Plugin}s to work properly
   * with build systems that were written when only a single {@link TaskListener} was supported at a
   * time.
   */
  @Override
  public void setTaskListener(@Nullable TaskListener taskListener) {
    if (singleTaskListener != null) {
      removeTaskListener(singleTaskListener);
    }
    singleTaskListener = taskListener;
    if (singleTaskListener != null) {
      addTaskListener(singleTaskListener);
    }
  }

  @Override
  public void addTaskListener(TaskListener taskListener) {
    taskListeners.addListener(taskListener);
  }

  @Override
  public void removeTaskListener(TaskListener taskListener) {
    taskListeners.removeListener(taskListener);
  }

  public void addPlugin(BuckJavacPlugin plugin, String... args) {
    pluginsAndArgs.put(plugin, args);
  }

  public void addPostEnterCallback(Consumer<Set<TypeElement>> callback) {
    postEnterCallbacks.add(callback);
  }

  public Trees getTrees() {
    return Trees.instance(inner);
  }

  protected void started(TaskEvent e) {
    // Initialize plugins just before sending the first event to registered listeners. We do it
    // this way (rather than initializing plugins just before starting to run the task) because
    // most plugins will call methods like getElements, which in javac 7 are not safe to call
    // before the task is actually running.
    installPlugins();

    taskListeners.started(e);
    postEnterTaskListener.started(e);
  }

  protected void finished(TaskEvent e) {
    taskListeners.finished(e);
    postEnterTaskListener.finished(e);
  }

  protected void onPostEnter(Set<TypeElement> topLevelTypes) {
    postEnterCallbacks.forEach(callback -> callback.accept(topLevelTypes));
  }

  private void installPlugins() {
    if (pluginsInstalled) {
      return;
    }

    for (Map.Entry<BuckJavacPlugin, String[]> pluginAndArgs : pluginsAndArgs.entrySet()) {
      pluginAndArgs.getKey().init(BuckJavacTask.this, pluginAndArgs.getValue());
    }

    pluginsAndArgs.clear();
    pluginsInstalled = true;
  }
}
