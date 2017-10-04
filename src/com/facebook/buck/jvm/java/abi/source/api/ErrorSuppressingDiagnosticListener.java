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

package com.facebook.buck.jvm.java.abi.source.api;

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;

/**
 * When running the compiler with some dependencies missing (as when generating ABIs from source),
 * by default it will abort after the last round of annotation processing because it believes there
 * are missing types. This diagnostic listener tricks the compiler into ignoring those errors (which
 * it marks as "recoverable") so that the compiler will keep going and run one more enter phase
 * after annotation processing. We must be careful to not allow it to run further, because the
 * analyze phase will not be happy to have missing types.
 */
public class ErrorSuppressingDiagnosticListener implements DiagnosticListener<JavaFileObject> {
  private final DiagnosticListener<? super JavaFileObject> inner;
  @Nullable private JavaCompiler.CompilationTask task;
  @Nullable private Object log;
  @Nullable private Field nErrorsField;
  @Nullable private Enum<?> recoverableError;
  @Nullable private Method isFlagSetMethod;
  @Nullable private Field diagnosticField;

  public ErrorSuppressingDiagnosticListener(DiagnosticListener<? super JavaFileObject> inner) {
    this.inner = inner;
  }

  public void setTask(JavaCompiler.CompilationTask task) {
    this.task = task;
  }

  @Override
  public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
    ensureInitialized();
    if (diagnostic.getKind() != Diagnostic.Kind.ERROR || !isRecoverable(diagnostic)) {
      inner.report(diagnostic);
    } else {
      // Don't pass the error on to Buck. Also, decrement nerrors in the compiler so that it
      // won't prematurely stop compilation.
      decrementNErrors();
    }
  }

  private void decrementNErrors() {
    Field nErrorsField = Preconditions.checkNotNull(this.nErrorsField);

    try {
      int nerrors = (Integer) nErrorsField.get(log);
      nerrors -= 1;
      nErrorsField.set(log, nerrors);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean isRecoverable(Diagnostic<? extends JavaFileObject> diagnostic) {
    try {
      return (Boolean)
          Preconditions.checkNotNull(isFlagSetMethod)
              .invoke(
                  Preconditions.checkNotNull(diagnosticField).get(diagnostic), recoverableError);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private void ensureInitialized() {
    if (nErrorsField != null && log != null) {
      return;
    }

    try {
      Class<? extends JavaCompiler.CompilationTask> compilerClass =
          Preconditions.checkNotNull(task).getClass();
      Field contextField = compilerClass.getSuperclass().getDeclaredField("context");
      contextField.setAccessible(true);
      Object context = contextField.get(task);

      ClassLoader compilerClassLoader = compilerClass.getClassLoader();
      Class<?> logClass = Class.forName("com.sun.tools.javac.util.Log", false, compilerClassLoader);
      log = logClass.getMethod("instance", context.getClass()).invoke(null, context);
      nErrorsField = logClass.getField("nerrors");

      Class<?> diagnosticClass =
          Class.forName("com.sun.tools.javac.util.JCDiagnostic", false, compilerClassLoader);
      @SuppressWarnings("unchecked")
      Class<Enum<?>> diagnosticFlagClass =
          (Class<Enum<?>>)
              Class.forName(
                  "com.sun.tools.javac.util.JCDiagnostic$DiagnosticFlag",
                  false,
                  compilerClassLoader);
      isFlagSetMethod = diagnosticClass.getMethod("isFlagSet", diagnosticFlagClass);
      for (Enum<?> enumConstant : diagnosticFlagClass.getEnumConstants()) {
        if (enumConstant.name().equals("RECOVERABLE")) {
          recoverableError = enumConstant;
          break;
        }
      }
      Preconditions.checkNotNull(recoverableError);

      Class<?> diagnosticSourceUnwrapperClass =
          Class.forName(
              "com.sun.tools.javac.api.ClientCodeWrapper$DiagnosticSourceUnwrapper",
              false,
              compilerClassLoader);
      diagnosticField = diagnosticSourceUnwrapperClass.getField("d");
    } catch (ClassNotFoundException
        | IllegalAccessException
        | InvocationTargetException
        | NoSuchFieldException
        | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }
}
