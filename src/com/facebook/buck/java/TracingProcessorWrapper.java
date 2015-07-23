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

package com.facebook.buck.java;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTarget;

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
  private final BuckEventBus eventBus;
  private final Processor innerProcessor;
  private final BuildTarget buildTarget;
  private final String annotationProcessorName;

  private int roundNumber = 0;
  private boolean isLastRound = false;

  public TracingProcessorWrapper(
      BuckEventBus eventBus,
      BuildTarget buildTarget,
      Processor processor) {
    this.eventBus = eventBus;
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
    } finally {
      end(started);
    }
  }

  @Override
  public void init(ProcessingEnvironment processingEnv) {
    AnnotationProcessingEvent.Started started = begin(AnnotationProcessingEvent.Operation.INIT);
    try {
      innerProcessor.init(processingEnv);
    } finally {
      end(started);
    }
  }

  @Override
  public boolean process(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    roundNumber += 1;
    isLastRound = roundEnv.processingOver();
    AnnotationProcessingEvent.Started started = begin(AnnotationProcessingEvent.Operation.PROCESS);
    try {
      return innerProcessor.process(annotations, roundEnv);
    } finally {
      end(started);
    }
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
    AnnotationProcessingEvent.Started started = AnnotationProcessingEvent.started(
        buildTarget,
        annotationProcessorName,
        operation,
        roundNumber,
        isLastRound);
    eventBus.post(started);
    return started;
  }

  private void end(AnnotationProcessingEvent.Started started) {
    eventBus.post(AnnotationProcessingEvent.finished(started));
  }
}
