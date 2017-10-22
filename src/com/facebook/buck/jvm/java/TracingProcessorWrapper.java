/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.string.AsciiBoxStringBuilder;
import com.google.common.base.Throwables;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Completion;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/** Wraps an annotation processor, tracing all method calls to BuckEventBus. */
class TracingProcessorWrapper implements Processor {
  private final JavacEventSink eventSink;
  private final Processor innerProcessor;
  private final BuildTarget buildTarget;
  private final String annotationProcessorName;

  private int roundNumber = 0;
  private boolean isLastRound = false;

  public TracingProcessorWrapper(
      JavacEventSink eventSink, BuildTarget buildTarget, Processor processor) {
    this.eventSink = eventSink;
    this.buildTarget = buildTarget;
    innerProcessor = processor;
    annotationProcessorName = innerProcessor.getClass().getName();
  }

  @Override
  public Set<String> getSupportedOptions() {
    AnnotationProcessingEvent.Started started =
        begin(AnnotationProcessingEvent.Operation.GET_SUPPORTED_OPTIONS);
    try {
      return innerProcessor.getSupportedOptions();
    } catch (RuntimeException e) {
      throw wrapAnnotationProcessorCrashException(e);
    } finally {
      end(started);
    }
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    AnnotationProcessingEvent.Started started =
        begin(AnnotationProcessingEvent.Operation.GET_SUPPORTED_ANNOTATION_TYPES);
    try {
      return innerProcessor.getSupportedAnnotationTypes();
    } catch (RuntimeException e) {
      throw wrapAnnotationProcessorCrashException(e);
    } finally {
      end(started);
    }
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    AnnotationProcessingEvent.Started started =
        begin(AnnotationProcessingEvent.Operation.GET_SUPPORTED_SOURCE_VERSION);
    try {
      return innerProcessor.getSupportedSourceVersion();
    } catch (RuntimeException e) {
      throw wrapAnnotationProcessorCrashException(e);
    } finally {
      end(started);
    }
  }

  @Override
  public void init(ProcessingEnvironment processingEnv) {
    AnnotationProcessingEvent.Started started = begin(AnnotationProcessingEvent.Operation.INIT);
    try {
      innerProcessor.init(processingEnv);
    } catch (RuntimeException e) {
      throw wrapAnnotationProcessorCrashException(e);
    } finally {
      end(started);
    }
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    roundNumber += 1;
    isLastRound = roundEnv.processingOver();
    AnnotationProcessingEvent.Started started = begin(AnnotationProcessingEvent.Operation.PROCESS);
    try {
      return innerProcessor.process(annotations, roundEnv);
    } catch (RuntimeException e) {
      throw wrapAnnotationProcessorCrashException(e);
    } finally {
      end(started);
    }
  }

  private HumanReadableException wrapAnnotationProcessorCrashException(RuntimeException e) {
    List<String> filteredStackTraceLines = getStackTraceEndingAtAnnotationProcessor(e);

    int maxLineLength = filteredStackTraceLines.stream().mapToInt(String::length).max().orElse(75);

    AsciiBoxStringBuilder messageBuilder =
        new AsciiBoxStringBuilder(maxLineLength)
            .writeLine("The annotation processor %s has crashed.\n", annotationProcessorName)
            .writeLine(
                "This is likely a bug in the annotation processor itself, though there may be changes you can make to your code to work around it. Examine the exception stack trace below and consult the annotation processor's troubleshooting guide.\n");

    filteredStackTraceLines.forEach(messageBuilder::writeLine);

    return new HumanReadableException(e, "\n" + messageBuilder.toString());
  }

  private List<String> getStackTraceEndingAtAnnotationProcessor(RuntimeException e) {
    String[] stackTraceLines = Throwables.getStackTraceAsString(e).split("\n");
    List<String> filteredStackTraceLines = new ArrayList<>();

    boolean skippingBuckFrames = false;
    int numFramesSkipped = 0;
    for (String stackTraceLine : stackTraceLines) {
      if (stackTraceLine.contains(TracingProcessorWrapper.class.getSimpleName())) {
        skippingBuckFrames = true;
        numFramesSkipped = 0;
      } else if (stackTraceLine.contains("Caused by:")) {
        skippingBuckFrames = false;
        if (numFramesSkipped > 0) {
          // Mimic the skipped frames logic of inner exceptions for the frames we've skipped
          filteredStackTraceLines.add(String.format("\t... %d more", numFramesSkipped));
        }
      }

      if (skippingBuckFrames) {
        numFramesSkipped += 1;
        continue;
      }

      filteredStackTraceLines.add(stackTraceLine);
    }
    return filteredStackTraceLines;
  }

  @Override
  public Iterable<? extends Completion> getCompletions(
      Element element, AnnotationMirror annotation, ExecutableElement member, String userText) {
    AnnotationProcessingEvent.Started started =
        begin(AnnotationProcessingEvent.Operation.GET_COMPLETIONS);
    try {
      return innerProcessor.getCompletions(element, annotation, member, userText);
    } finally {
      end(started);
    }
  }

  private AnnotationProcessingEvent.Started begin(AnnotationProcessingEvent.Operation operation) {
    AnnotationProcessingEvent.Started started =
        AnnotationProcessingEvent.started(
            buildTarget, annotationProcessorName, operation, roundNumber, isLastRound);
    eventSink.reportAnnotationProcessingEventStarted(
        buildTarget, annotationProcessorName, operation.toString(), roundNumber, isLastRound);
    return started;
  }

  private void end(AnnotationProcessingEvent.Started started) {
    eventSink.reportAnnotationProcessingEventFinished(
        started.getBuildTarget(),
        started.getAnnotationProcessorName(),
        started.getOperation().toString(),
        started.getRound(),
        started.isLastRound());
  }
}
