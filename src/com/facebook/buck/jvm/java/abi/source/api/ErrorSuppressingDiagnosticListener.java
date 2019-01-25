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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
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
  @Nullable private Field contextField;
  @Nullable private Method instanceMethod;
  @Nullable private Field nErrorsField;
  @Nullable private Field recordedField;
  @Nullable private Enum<?> recoverableError;
  @Nullable private Method isFlagSetMethod;
  @Nullable private Field diagnosticField;
  @Nullable private Field pairFirstField;
  @Nullable private Field pairSecondField;

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

      // Log uses a set of file, offset pairs to prevent reporting the same error twice. Since
      // we suppressed this error, we should allow other (non-suppressed) errors to be reported
      // in the same location
      removeFromRecordedSet(diagnostic);
    }
  }

  private void decrementNErrors() {
    Field nErrorsField = Objects.requireNonNull(this.nErrorsField);

    try {
      int nerrors = (Integer) nErrorsField.get(getLog());
      nerrors -= 1;
      nErrorsField.set(getLog(), nerrors);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private void removeFromRecordedSet(Diagnostic<? extends JavaFileObject> diagnostic) {
    JavaFileObject file = diagnostic.getSource();
    if (file == null) {
      return;
    }

    try {
      Set<?> recorded = (Set<?>) Objects.requireNonNull(recordedField).get(getLog());
      if (recorded != null) {
        boolean removed = false;
        for (Object recordedPair : recorded) {
          JavaFileObject fst =
              (JavaFileObject) Objects.requireNonNull(pairFirstField).get(recordedPair);
          Integer snd = (Integer) Objects.requireNonNull(pairSecondField).get(recordedPair);

          if (snd == diagnostic.getPosition() && fst.getName().equals(file.getName())) {
            removed = recorded.remove(recordedPair);
            break;
          }
        }
        if (!removed) {
          throw new AssertionError(
              String.format(
                  "BUG! Failed to remove %s: %d", file.getName(), diagnostic.getPosition()));
        }
      }
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Gets the current log object, since the Log and the Context change for each round of annotation
   * processing.
   */
  private Object getLog() {
    try {
      Object context = Objects.requireNonNull(contextField).get(task);
      return Objects.requireNonNull(instanceMethod).invoke(null, context);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean isRecoverable(Diagnostic<? extends JavaFileObject> diagnostic) {
    try {
      // There's a bug in javac whereby under certain circumstances it will assign its default
      // flag set to a Diagnostic object without copying it first, and then mutate the set
      // such that it contains the "recoverable" flag. That can cause missing class file errors
      // to be reported as recoverable, when they're really not.
      if (diagnostic.getCode().equals("compiler.err.cant.access")
          || diagnostic.getCode().equals("compiler.err.proc.messager")) {
        return false;
      }

      return (boolean)
          Objects.requireNonNull(isFlagSetMethod)
              .invoke(Objects.requireNonNull(diagnosticField).get(diagnostic), recoverableError);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private void ensureInitialized() {
    if (nErrorsField != null) {
      return;
    }

    try {
      Class<? extends JavaCompiler.CompilationTask> compilerClass =
          Objects.requireNonNull(task).getClass();
      contextField = compilerClass.getSuperclass().getDeclaredField("context");
      contextField.setAccessible(true);
      Object context = contextField.get(task);

      ClassLoader compilerClassLoader = compilerClass.getClassLoader();
      Class<?> logClass = Class.forName("com.sun.tools.javac.util.Log", false, compilerClassLoader);
      instanceMethod = logClass.getMethod("instance", context.getClass());
      nErrorsField = logClass.getField("nerrors");
      recordedField = logClass.getDeclaredField("recorded");
      recordedField.setAccessible(true);

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
      Objects.requireNonNull(recoverableError);

      Class<?> diagnosticSourceUnwrapperClass =
          Class.forName(
              "com.sun.tools.javac.api.ClientCodeWrapper$DiagnosticSourceUnwrapper",
              false,
              compilerClassLoader);
      diagnosticField = diagnosticSourceUnwrapperClass.getField("d");

      Class<?> pairClass =
          Class.forName("com.sun.tools.javac.util.Pair", false, compilerClassLoader);
      pairFirstField = pairClass.getField("fst");
      pairSecondField = pairClass.getField("snd");
    } catch (ClassNotFoundException
        | IllegalAccessException
        | NoSuchFieldException
        | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }
}
