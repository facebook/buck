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

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.tools.JavaCompiler;

/**
 * When running the compiler with some dependencies missing (as when generating ABIs from source),
 * by default it will abort after the last round of annotation processing because it believes there
 * are errors. This task listener causes the compiler to discard errors detected during the Enter
 * phase, so that the compiler will keep going. After annotation processing, the compiler will run
 * one more enter phase. We must be careful to not allow it to run further, because the analyze
 * phase will not be happy to have missing types.
 */
class SuppressEnterErrorsTaskListener implements TaskListener {
  private final JavacTask task;

  @Nullable private Object log;
  @Nullable private Constructor<?> discardingHandlerConstructor;
  @Nullable private Method popDiagnosticHandlerMethod;

  @Nullable private Object discardingHandler;
  private int entersPending = 0;

  public SuppressEnterErrorsTaskListener(JavaCompiler.CompilationTask task) {
    this.task = (JavacTask) task;
  }

  @Override
  public void started(TaskEvent e) {
    if (e.getKind() == TaskEvent.Kind.ENTER) {
      entersPending += 1;

      if (entersPending == 1) {
        suppressErrors();
      }
    }
  }

  @Override
  public void finished(TaskEvent e) {
    if (e.getKind() == TaskEvent.Kind.ENTER) {
      entersPending -= 1;

      if (entersPending == 0) {
        restoreErrors();
      }
    }
  }

  private void suppressErrors() {
    ensureInitialized();
    try {
      discardingHandler = Preconditions.checkNotNull(discardingHandlerConstructor).newInstance(log);
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private void restoreErrors() {
    try {
      Preconditions.checkNotNull(popDiagnosticHandlerMethod).invoke(log, discardingHandler);
      discardingHandler = null;
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private void ensureInitialized() {
    try {
      Field contextField = task.getClass().getSuperclass().getDeclaredField("context");
      contextField.setAccessible(true);
      Object context = contextField.get(task);

      Class<?> logClass = Class.forName("com.sun.tools.javac.util.Log");
      log = logClass.getMethod("instance", context.getClass()).invoke(null, context);

      popDiagnosticHandlerMethod =
          logClass.getMethod(
              "popDiagnosticHandler",
              Class.forName("com.sun.tools.javac.util.Log$DiagnosticHandler"));
      discardingHandlerConstructor =
          Class.forName("com.sun.tools.javac.util.Log$DiscardDiagnosticHandler")
              .getConstructor(log.getClass());
    } catch (ClassNotFoundException
        | IllegalAccessException
        | InvocationTargetException
        | NoSuchFieldException
        | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }
}
