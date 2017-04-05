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

import java.io.IOException;
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
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

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

  private ProcessingEnvironment wrap(ProcessingEnvironment javacProcessingEnvironment) {
    return new ProcessingEnvironment() {
      @Override
      public Map<String, String> getOptions() {
        return javacProcessingEnvironment.getOptions();
      }

      @Override
      public Messager getMessager() {
        return wrap(javacProcessingEnvironment.getMessager());
      }

      @Override
      public Filer getFiler() {
        return wrap(javacProcessingEnvironment.getFiler());
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
        return javacProcessingEnvironment.getSourceVersion();
      }

      @Override
      public Locale getLocale() {
        return javacProcessingEnvironment.getLocale();
      }
    };
  }

  private RoundEnvironment wrap(RoundEnvironment javacRoundEnvironment) {
    return new RoundEnvironment() {
      @Override
      public boolean processingOver() {
        return javacRoundEnvironment.processingOver();
      }

      @Override
      public boolean errorRaised() {
        return javacRoundEnvironment.errorRaised();
      }

      @Override
      public Set<? extends Element> getRootElements() {
        return javacRoundEnvironment.getRootElements().stream()
            .map(elements::getCanonicalElement)
            .collect(Collectors.toSet());
      }

      @Override
      public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {
        return javacRoundEnvironment.getElementsAnnotatedWith(elements.getJavacElement(a)).stream()
            .map(elements::getCanonicalElement)
            .collect(Collectors.toSet());
      }

      @Override
      public Set<? extends Element> getElementsAnnotatedWith(Class<? extends Annotation> a) {
        return javacRoundEnvironment.getElementsAnnotatedWith(a).stream()
            .map(elements::getCanonicalElement)
            .collect(Collectors.toSet());
      }
    };
  }

  private Messager wrap(Messager javacMessager) {
    return new Messager() {
      @Override
      public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
        javacMessager.printMessage(kind, msg);
      }

      @Override
      public void printMessage(
          Diagnostic.Kind kind, CharSequence msg, Element e) {
        javacMessager.printMessage(kind, msg, elements.getJavacElement(e));
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

  private Filer wrap(Filer javacFiler) {
    return new Filer() {
      @Override
      public JavaFileObject createSourceFile(
          CharSequence name, Element... originatingElements) throws IOException {
        return javacFiler.createSourceFile(name, elements.getJavacElements(originatingElements));
      }

      @Override
      public JavaFileObject createClassFile(
          CharSequence name, Element... originatingElements) throws IOException {
        return javacFiler.createClassFile(name, elements.getJavacElements(originatingElements));
      }

      @Override
      public FileObject createResource(
          JavaFileManager.Location location,
          CharSequence pkg,
          CharSequence relativeName,
          Element... originatingElements) throws IOException {
        return javacFiler.createResource(
            location,
            pkg,
            relativeName,
            elements.getJavacElements(originatingElements));
      }

      @Override
      public FileObject getResource(
          JavaFileManager.Location location,
          CharSequence pkg,
          CharSequence relativeName) throws IOException {
        return javacFiler.getResource(location, pkg, relativeName);
      }
    };
  }
}
