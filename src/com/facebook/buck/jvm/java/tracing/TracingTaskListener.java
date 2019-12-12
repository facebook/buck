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

package com.facebook.buck.jvm.java.tracing;

import com.facebook.buck.jvm.java.plugin.api.BuckJavacTaskListener;
import com.facebook.buck.jvm.java.plugin.api.TaskEventMirror;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A {@link BuckJavacTaskListener} that traces all events to a {@link JavacPhaseTracer}. The event
 * stream coming from the compiler is a little dirty, so this class cleans it up minimally before
 * tracing.
 */
public class TracingTaskListener implements BuckJavacTaskListener {
  private final JavacPhaseTracer tracing;
  private final TraceCleaner traceCleaner;
  @Nullable private final BuckJavacTaskListener inner;

  public TracingTaskListener(JavacPhaseTracer tracing, @Nullable BuckJavacTaskListener next) {
    inner = next;
    this.tracing = tracing;
    traceCleaner = new TraceCleaner(tracing);
  }

  @Override
  public synchronized void started(TaskEventMirror e) {
    // Chain start events before tracing, so that the tracing most closely tracks the actual event
    // time
    if (inner != null) {
      inner.started(e);
    }

    switch (e.getKind()) {
      case PARSE:
        tracing.beginParse(getFileName(e));
        break;
      case ENTER:
        traceCleaner.startEnter(e.getSourceFile().getName());
        break;
      case ANNOTATION_PROCESSING_ROUND:
        tracing.beginAnnotationProcessingRound();
        break;
      case ANALYZE:
        traceCleaner.startAnalyze(getFileName(e), getTypeName(e));
        break;
      case GENERATE:
        tracing.beginGenerate(getFileName(e), getTypeName(e));
        break;
      case ANNOTATION_PROCESSING:
      case COMPILATION:
      default:
        // The tracing doesn't care about these events
        break;
    }
  }

  @Override
  public synchronized void finished(TaskEventMirror e) {
    // See comment in started() about testability.
    switch (e.getKind()) {
      case PARSE:
        tracing.endParse();
        break;
      case ENTER:
        traceCleaner.finishEnter();
        break;
      case ANNOTATION_PROCESSING_ROUND:
        tracing.endAnnotationProcessingRound();
        break;
      case ANALYZE:
        traceCleaner.finishAnalyze(getFileName(e), getTypeName(e));
        break;
      case GENERATE:
        tracing.endGenerate();
        break;
      case ANNOTATION_PROCESSING:
        // Javac considers annotation processing to be done after files generated in the last
        // round have been parsed, but before they are entered. Our tracing wants to include the
        // enter (since it would not have happened without annotation processing, we want to
        // attribute its time to annotation processing), so we just tell the tracer that the
        // current round is the last one.
        tracing.setIsLastRound(true);
        break;
      case COMPILATION:
      default:
        // The tracing doesn't care about these events
        break;
    }

    // Chain finished events after tracing, so that the tracing most closely tracks the actual
    // event time
    if (inner != null) {
      inner.finished(e);
    }
  }

  /** Cleans up the slightly dirty aspects of the event stream. */
  static class TraceCleaner {
    private final JavacPhaseTracer tracing;
    private int enterCount = 0;
    private List<String> enteredFiles = new ArrayList<>();
    private int analyzeCount = 0;
    private List<String> analyzedFiles = new ArrayList<>();
    private List<String> analyzedTypes = new ArrayList<>();

    public TraceCleaner(JavacPhaseTracer tracing) {
      this.tracing = tracing;
    }

    /**
     * Generally the compiler enters all compilation units at once. It fires start events for all of
     * them, does the work, then fires finish events for all of them. There are a couple of issues
     * with this from a tracing perspective:
     *
     * <ul>
     *   <li>It generates a ton of slices
     *   <li>The finish events are in the same order as the start ones (we'd expect them to be in
     *       reverse order if this were true tracing)
     * </ul>
     *
     * To clean this up into a trace-friendly event stream, we coalesce all the enter events for a
     * given round into one.
     *
     * @see #finishEnter()
     */
    void startEnter(String filename) {
      if (enterCount == 0) {
        tracing.beginEnter();
      }

      enterCount += 1;
      enteredFiles.add(filename);
    }

    /** @see #startEnter(String) */
    void finishEnter() {
      enterCount -= 1;

      if (enterCount == 0) {
        tracing.endEnter(enteredFiles);
        enteredFiles.clear();
      }
    }

    /**
     * There are some cases in which a finish analyze event can be received without a corresponding
     * start. Rather than output malformed trace data, we detect that case and synthesize a start
     * event. There are other cases in which all analyze start events are issued together, and then
     * all analyze finish events. In those cases, we consolidate into one event.
     *
     * @see #finishAnalyze(String, String)
     */
    void startAnalyze(@Nullable String filename, @Nullable String typename) {
      analyzedFiles.add(filename);
      analyzedTypes.add(typename);

      analyzeCount += 1;
      if (analyzeCount == 1) {
        tracing.beginAnalyze();
      }
    }

    /** @see #startAnalyze(String, String) */
    void finishAnalyze(@Nullable String filename, @Nullable String typename) {
      if (analyzeCount > 0) {
        analyzeCount -= 1;
        if (analyzeCount == 0) {
          tracing.endAnalyze(analyzedFiles, analyzedTypes);
        }
      } else {
        tracing.beginAnalyze();
        tracing.endAnalyze(
            Collections.singletonList(filename), Collections.singletonList(typename));
      }
    }
  }

  @Nullable
  private static String getFileName(TaskEventMirror e) {
    if (e.getSourceFile() == null) {
      return null;
    }

    return e.getSourceFile().toUri().toString();
  }

  @Nullable
  private static String getTypeName(TaskEventMirror e) {
    if (e.getTypeElement() == null) {
      return null;
    }

    return e.getTypeElement().getQualifiedName().toString();
  }
}
