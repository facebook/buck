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
    try (Scope scope = new Scope(AnnotationProcessingEvent.Operation.GET_SUPPORTED_OPTIONS)) {
      return innerProcessor.getSupportedOptions();
    } catch (RuntimeException | Error e) {
      throw wrapAnnotationProcessorCrashException(e);
    }
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    try (Scope scope =
        new Scope(AnnotationProcessingEvent.Operation.GET_SUPPORTED_ANNOTATION_TYPES)) {
      return innerProcessor.getSupportedAnnotationTypes();
    } catch (RuntimeException | Error e) {
      throw wrapAnnotationProcessorCrashException(e);
    }
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    try (Scope scope =
        new Scope(AnnotationProcessingEvent.Operation.GET_SUPPORTED_SOURCE_VERSION)) {
      return innerProcessor.getSupportedSourceVersion();
    } catch (RuntimeException | Error e) {
      throw wrapAnnotationProcessorCrashException(e);
    }
  }

  @Override
  public void init(ProcessingEnvironment processingEnv) {
    try (Scope scope = new Scope(AnnotationProcessingEvent.Operation.INIT)) {
      innerProcessor.init(processingEnv);
    } catch (RuntimeException | Error e) {
      throw wrapAnnotationProcessorCrashException(e);
    }
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    roundNumber += 1;
    isLastRound = roundEnv.processingOver();
    try (Scope scope = new Scope(AnnotationProcessingEvent.Operation.PROCESS)) {
      return innerProcessor.process(annotations, roundEnv);
    } catch (RuntimeException | Error e) {
      throw wrapAnnotationProcessorCrashException(e);
    }
  }

  private HumanReadableException wrapAnnotationProcessorCrashException(Throwable e) {
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

  private List<String> getStackTraceEndingAtAnnotationProcessor(Throwable e) {
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
    try (Scope scope = new Scope(AnnotationProcessingEvent.Operation.GET_COMPLETIONS)) {
      return innerProcessor.getCompletions(element, annotation, member, userText);
    }
  }

  private class Scope implements AutoCloseable {
    private final AnnotationProcessingEvent.Started started;

    public Scope(AnnotationProcessingEvent.Operation operation) {
      started = begin(operation);
    }

    @Override
    public void close() {
      end(started);
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
}
