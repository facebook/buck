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

import java.lang.annotation.Annotation;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Wraps an annotation processor to ensure that it always sees canonical elements -- that is,
 * {@link TreeBackedElement}s when they are available, javac elements when they are not.
 *
 * Annotation processors that depend on compiler internals or {@link com.sun.source.util.Trees}
 * will not run properly (typically they will crash) when run inside this wrapper.
 */
class TreeBackedProcessorWrapper implements Processor {
  private final Processor inner;
  private final TreeBackedElements elements;
  private final TreeBackedTypes types;

  TreeBackedProcessorWrapper(TreeBackedElements elements, TreeBackedTypes types, Processor inner) {
    this.inner = inner;
    this.elements = elements;
    this.types = types;
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
    inner.init(wrap(processingEnv));
  }

  @Override
  public Iterable<? extends Completion> getCompletions(
      Element element,
      AnnotationMirror annotation,
      ExecutableElement member,
      String userText) {
    // This method is only ever called from IDEs, which is not a scenario for Buck right now
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    return inner.process(
        annotations.stream().map(elements::getCanonicalElement).collect(Collectors.toSet()),
        wrap(roundEnv));
  }

  private ProcessingEnvironment wrap(ProcessingEnvironment inner) {
    return new ProcessingEnvironment() {
      @Override
      public Map<String, String> getOptions() {
        return inner.getOptions();
      }

      @Override
      public Messager getMessager() {
        return wrap(inner.getMessager());
      }

      @Override
      public Filer getFiler() {
        return inner.getFiler();
      }

      @Override
      public Elements getElementUtils() {
        return elements;
      }

      @Override
      public Types getTypeUtils() {
        return types;
      }

      @Override
      public SourceVersion getSourceVersion() {
        return inner.getSourceVersion();
      }

      @Override
      public Locale getLocale() {
        return inner.getLocale();
      }
    };
  }

  private RoundEnvironment wrap(RoundEnvironment inner) {
    return new RoundEnvironment() {
      @Override
      public boolean processingOver() {
        return inner.processingOver();
      }

      @Override
      public boolean errorRaised() {
        return inner.errorRaised();
      }

      @Override
      public Set<? extends Element> getRootElements() {
        return inner.getRootElements().stream()
            .map(elements::getCanonicalElement)
            .collect(Collectors.toSet());
      }

      @Override
      public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {
        return inner.getElementsAnnotatedWith(elements.getJavacElement(a)).stream()
            .map(elements::getCanonicalElement)
            .collect(Collectors.toSet());
      }

      @Override
      public Set<? extends Element> getElementsAnnotatedWith(Class<? extends Annotation> a) {
        return inner.getElementsAnnotatedWith(a).stream()
            .map(elements::getCanonicalElement)
            .collect(Collectors.toSet());
      }
    };
  }

  private Messager wrap(Messager inner) {
    return new Messager() {
      @Override
      public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
        inner.printMessage(kind, msg);
      }

      @Override
      public void printMessage(
          Diagnostic.Kind kind, CharSequence msg, Element e) {
        inner.printMessage(kind, msg, elements.getJavacElement(e));
      }

      @Override
      public void printMessage(
          Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
        throw new UnsupportedOperationException("Annotations NYI");
      }

      @Override
      public void printMessage(
          Diagnostic.Kind kind,
          CharSequence msg,
          Element e,
          AnnotationMirror a,
          AnnotationValue v) {
        throw new UnsupportedOperationException("Annotations NYI");
      }
    };
  }
}
