/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.java.abi;

import static javax.lang.model.SourceVersion.RELEASE_7;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

@SupportedSourceVersion(RELEASE_7)
@SupportedAnnotationTypes("*")
public class AbiWriter extends AbstractProcessor {

  private ImmutableSortedSet.Builder<String> classes = ImmutableSortedSet.naturalOrder();

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    RenderableTypes factory = new RenderableTypes();

    for (Element element : roundEnv.getRootElements()) {
      if (element instanceof TypeElement) {
        Renderable renderable = factory.deriveFor(element);
        StringBuilder builder = new StringBuilder();
        renderable.appendTo(builder);
        classes.add(builder.toString());
      } else {
        throw new RuntimeException("Unknown type: " + element.getKind());
      }
    }

    return true;
  }

  public ImmutableSet<String> getSummaries() {
    return classes.build();
  }
}
