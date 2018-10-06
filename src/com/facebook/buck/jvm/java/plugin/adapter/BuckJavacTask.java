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

import com.facebook.buck.jvm.java.lang.model.ElementsExtended;
import com.facebook.buck.util.liteinfersupport.Nullable;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Extends {@link JavacTask} with functionality that is useful for Buck:
 *
 * <ul>
 *   <li>Exposes the enter method from JavacTaskImpl
 *   <li>Pre-javac-8 support for addTaskListener/removeTaskListener
 *   <li>Wraps {@link Elements} with some extended functionality
 * </ul>
 */
public class BuckJavacTask extends JavacTaskWrapper {
  private final Map<BuckJavacPlugin, String[]> pluginsAndArgs = new LinkedHashMap<>();
  private final MultiplexingTaskListener taskListeners = new MultiplexingTaskListener();
  private final PostEnterTaskListener postEnterTaskListener;
  private final List<Consumer<Set<Element>>> postEnterCallbacks = new ArrayList<>();

  private boolean pluginsInstalled = false;

  @Nullable private TaskListener singleTaskListener;
  @Nullable private ElementsExtended elements;

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

  @Override
  public Iterable<? extends CompilationUnitTree> parse() throws IOException {
    Iterable<? extends CompilationUnitTree> result = super.parse();
    JavacTaskDiagnosticsBugWorkaround.apply(inner);
    return result;
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
      } else if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      } else if (e.getCause() instanceof Error) {
        throw (Error) e.getCause();
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

  @Override
  public ElementsExtended getElements() {
    if (elements == null) {
      elements = new ElementsExtendedImpl(super.getElements(), getTypes(), getTrees());
    }
    return elements;
  }

  public void addPlugin(BuckJavacPlugin plugin, String... args) {
    pluginsAndArgs.put(plugin, args);
  }

  public void addPostEnterCallback(Consumer<Set<Element>> callback) {
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

  protected void onPostEnter(Set<Element> topLevelElements) {
    postEnterCallbacks.forEach(callback -> callback.accept(topLevelElements));
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

  /**
   * JavacTaskImpl does not report errors that occur between parse and enter until after enter (on
   * any version of javac). In a just world, that would only result in extra spew. However, on javac
   * 8 and older, there's another bug that allows fatal diagnostics to get marked as recoverable
   * sometimes in the presence of annotation processors. Thus in javac 8 and older, when invoking
   * the compiler phases separately, these errors get swallowed and the compiler crashes in later
   * phases.
   *
   * <p>This workaround reaches into the compiler to force it to report diagnostics between parse
   * and enter, thus allowing the logic in Jsr199JavacInvocation to detect the error and stop.
   */
  private static final class JavacTaskDiagnosticsBugWorkaround {
    @Nullable private static volatile Class<? extends JavacTask> javacTaskClass;
    @Nullable private static volatile Field compilerField;
    @Nullable private static volatile Field deferredDiagnosticHandlerField;
    @Nullable private static volatile Method getDiagnosticsMethod;
    @Nullable private static volatile Method reportDeferredDiagnosticsMethod;

    public static void apply(JavacTask inner) {
      try {
        Object compiler = getCompiler(inner);
        Object deferredDiagnosticHandler = getDeferredDiagnosticHandler(compiler);

        if (deferredDiagnosticHandler != null) {
          Queue<Diagnostic<JavaFileObject>> deferredDiagnostics =
              getDiagnostics(deferredDiagnosticHandler);

          if (deferredDiagnostics
              .stream()
              .anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)) {
            // The queue gets nulled out after reporting, so only report when we know the build will
            // be failing
            reportDeferredDiagnostics(deferredDiagnosticHandler);
          }
        }
      } catch (IllegalAccessException // NOPMD
          | NoSuchFieldException
          | NoSuchMethodException e) {
        // Do nothing; must not be javac 8
      } catch (InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }

    private static Object getCompiler(JavacTask inner)
        throws NoSuchFieldException, IllegalAccessException {
      Class<? extends JavacTask> javacTaskClass = getJavacTaskClass(inner);
      if (compilerField == null) {
        synchronized (JavacTaskDiagnosticsBugWorkaround.class) {
          if (compilerField == null) {
            compilerField = javacTaskClass.getDeclaredField("compiler");
            compilerField.setAccessible(true);
          }
        }
      }

      return Objects.requireNonNull(compilerField.get(inner));
    }

    private static Class<? extends JavacTask> getJavacTaskClass(JavacTask task) {
      if (javacTaskClass == null) {
        synchronized (JavacTaskDiagnosticsBugWorkaround.class) {
          if (javacTaskClass == null) {
            javacTaskClass = task.getClass();
          }
        }
      }

      if (task.getClass() != javacTaskClass) {
        // This code is running in a plugin loaded into the compiler's class laoder, so there should
        // only be one type of JavacTask in use.
        throw new AssertionError();
      }
      return javacTaskClass;
    }

    @Nullable
    private static Object getDeferredDiagnosticHandler(Object compiler)
        throws IllegalAccessException, NoSuchFieldException {
      if (deferredDiagnosticHandlerField == null) {
        synchronized (JavacTaskDiagnosticsBugWorkaround.class) {
          if (deferredDiagnosticHandlerField == null) {
            deferredDiagnosticHandlerField =
                compiler.getClass().getDeclaredField("deferredDiagnosticHandler");
            deferredDiagnosticHandlerField.setAccessible(true);
          }
        }
      }

      return deferredDiagnosticHandlerField.get(compiler);
    }

    private static Queue<Diagnostic<JavaFileObject>> getDiagnostics(
        Object deferredDiagnosticHandler)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
      if (getDiagnosticsMethod == null) {
        synchronized (JavacTaskDiagnosticsBugWorkaround.class) {
          if (getDiagnosticsMethod == null) {
            getDiagnosticsMethod = deferredDiagnosticHandler.getClass().getMethod("getDiagnostics");
          }
        }
      }

      @SuppressWarnings("unchecked")
      Queue<Diagnostic<JavaFileObject>> result =
          (Queue<Diagnostic<JavaFileObject>>)
              Objects.requireNonNull(getDiagnosticsMethod.invoke(deferredDiagnosticHandler));
      return result;
    }

    private static void reportDeferredDiagnostics(Object deferredDiagnosticHandler)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
      if (reportDeferredDiagnosticsMethod == null) {
        synchronized (JavacTaskDiagnosticsBugWorkaround.class) {
          if (reportDeferredDiagnosticsMethod == null) {
            reportDeferredDiagnosticsMethod =
                deferredDiagnosticHandler.getClass().getMethod("reportDeferredDiagnostics");
          }
        }
      }

      reportDeferredDiagnosticsMethod.invoke(deferredDiagnosticHandler);
    }
  }
}
