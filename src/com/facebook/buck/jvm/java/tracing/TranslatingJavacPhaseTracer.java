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

import java.util.List;
import javax.annotation.Nullable;

/**
 * A {@link JavacPhaseTracer} that translates the trace data to be more useful.
 *
 * <p>The phases of compilation are described <a
 * href="http://openjdk.java.net/groups/compiler/doc/compilation-overview/index.html">here</a>. The
 * doc describes annotation processing as conceptually occuring before compilation, but actually
 * occurring somewhat out-of-phase with the conceptual model.
 *
 * <p>Javac calls {@link TracingTaskListener} according to the conceptual model described in that
 * document: annotation processing starts at the very beginning of the run, and ends after the last
 * annotation processor is run in the last round of processing. Then there is one last parse and
 * enter before going into analyze and generate. This is problematic from a performance perspective,
 * because some of the work attributed to annotation processing would have happened regardless.
 *
 * <p>This class translates the tracing data from the conceptual model back into something that more
 * closely matches the actual implementation:
 *
 * <ul>
 *   <li>Parse, enter, analyze, and generate phases pass thru unchanged
 *   <li>What javac traces as an annotation processing round is renamed "run annotation processors"
 *   <li>Annotation processing rounds are traced from the beginning of "run annotation processors"
 *       to the beginning of the next "run annotation processors" or (for the last round) the first
 *       analyze phase
 *   <li>Annotation processing is traced from the beginning of the first round to the end of the
 *       last
 *   <li>If compilation ends during annotation processing (as can happen with -proc:only), it
 *       detects this (via being closed by its caller) and emits appropriate tracing
 * </ul>
 *
 * In this way, the time attributed to annotation processing is always time that would not have been
 * spent if annotation processors were not present.
 */
public class TranslatingJavacPhaseTracer implements JavacPhaseTracer, AutoCloseable {
  private final JavacPhaseEventLogger logger;

  private boolean isProcessingAnnotations = false;
  private int roundNumber = 0;
  private boolean isLastRound = false;

  public TranslatingJavacPhaseTracer(JavacPhaseEventLogger logger) {
    this.logger = logger;
  }

  @Override
  public void beginParse(@Nullable String filename) {
    logger.beginParse(filename);
  }

  @Override
  public void endParse() {
    logger.endParse();
  }

  @Override
  public void beginEnter() {
    logger.beginEnter();
  }

  @Override
  public void endEnter(List<String> filenames) {
    logger.endEnter(filenames);
    if (isProcessingAnnotations && isLastRound) {
      endAnnotationProcessing();
    }
  }

  @Override
  public void setIsLastRound(boolean isLastRound) {
    this.isLastRound = isLastRound;
  }

  @Override
  public void beginAnnotationProcessingRound() {
    if (isProcessingAnnotations) {
      logger.endAnnotationProcessingRound(false);
    } else {
      logger.beginAnnotationProcessing();
    }

    isProcessingAnnotations = true;
    roundNumber += 1;
    logger.beginAnnotationProcessingRound(roundNumber);
    logger.beginRunAnnotationProcessors();
  }

  @Override
  public void endAnnotationProcessingRound() {
    logger.endRunAnnotationProcessors();
  }

  @Override
  public void beginAnalyze() {
    logger.beginAnalyze();
  }

  @Override
  public void endAnalyze(List<String> filenames, List<String> typenames) {
    logger.endAnalyze(filenames, typenames);
  }

  @Override
  public void beginGenerate(@Nullable String filename, @Nullable String typename) {
    logger.beginGenerate(filename, typename);
  }

  @Override
  public void endGenerate() {
    logger.endGenerate();
  }

  @Override
  public void close() {
    // If javac is invoked with -proc:only, the last thing we'll hear from it is the end of
    // the annotation processing round. We won't get a beginAnalyze (or even a beginEnter) after
    // the annotation processors run for the last time.
    maybeEndAnnotationProcessing();
  }

  private void maybeEndAnnotationProcessing() {
    if (isProcessingAnnotations && isLastRound) {
      logger.endAnnotationProcessingRound(true);
      logger.endAnnotationProcessing();
      isProcessingAnnotations = false;
    }
  }

  private void endAnnotationProcessing() {
    logger.endAnnotationProcessingRound(true);
    logger.endAnnotationProcessing();
    isProcessingAnnotations = false;
  }
}
