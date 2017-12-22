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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

class TreeBackedRoundEnvironment implements RoundEnvironment {
  private final FrontendOnlyJavacTask task;
  private final RoundEnvironment javacRoundEnvironment;

  public TreeBackedRoundEnvironment(
      FrontendOnlyJavacTask task, RoundEnvironment javacRoundEnvironment) {
    this.task = task;
    this.javacRoundEnvironment = javacRoundEnvironment;
  }

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
    return javacRoundEnvironment
        .getRootElements()
        .stream()
        .map(task.getElements()::getCanonicalElement)
        .collect(toSet());
  }

  @Override
  public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {
    return javacRoundEnvironment
        .getElementsAnnotatedWith(task.getElements().getJavacElement(a))
        .stream()
        .map(task.getElements()::getCanonicalElement)
        .collect(toSet());
  }

  @Override
  public Set<? extends Element> getElementsAnnotatedWith(Class<? extends Annotation> a) {
    return javacRoundEnvironment
        .getElementsAnnotatedWith(a)
        .stream()
        .map(task.getElements()::getCanonicalElement)
        .collect(toSet());
  }

  private Collector<Element, ?, Set<Element>> toSet() {
    return Collectors.toCollection(LinkedHashSet::new);
  }
}
