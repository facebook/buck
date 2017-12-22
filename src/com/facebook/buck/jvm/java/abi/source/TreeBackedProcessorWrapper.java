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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import javax.annotation.processing.Completion;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Wraps an annotation processor to ensure that it always sees canonical elements -- that is, {@link
 * TreeBackedElement}s when they are available, javac elements when they are not.
 *
 * <p>Annotation processors that depend on compiler internals or {@link com.sun.source.util.Trees}
 * will not run properly (typically they will crash) when run inside this wrapper.
 */
class TreeBackedProcessorWrapper implements Processor {
  private final Processor inner;
  private final FrontendOnlyJavacTask task;

  TreeBackedProcessorWrapper(FrontendOnlyJavacTask task, Processor inner) {
    this.task = task;
    this.inner = inner;
  }

  @Override
  public Set<String> getSupportedOptions() {
    return inner.getSupportedOptions();
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return inner.getSupportedAnnotationTypes();
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return inner.getSupportedSourceVersion();
  }

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    inner.init(new TreeBackedProcessingEnvironment(task, processingEnv));
  }

  @Override
  public Iterable<? extends Completion> getCompletions(
      Element element, AnnotationMirror annotation, ExecutableElement member, String userText) {
    // This method is only ever called from IDEs, which is not a scenario for Buck right now
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    return inner.process(
        annotations.stream().map(task.getElements()::getCanonicalElement).collect(toSet()),
        new TreeBackedRoundEnvironment(task, roundEnv));
  }

  private Collector<TypeElement, ?, Set<TypeElement>> toSet() {
    return Collectors.toCollection(LinkedHashSet::new);
  }
}
