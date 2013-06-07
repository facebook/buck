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

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.Map;
import java.util.SortedSet;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;

class Annotations {

  private static final Function<Map.Entry<? extends ExecutableElement, ? extends AnnotationValue>, String> ANNOTATION_VALUE_TO_STRING =
      new Function<Map.Entry<? extends ExecutableElement, ? extends AnnotationValue>, String>() {
        @Override
        public String apply(Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> value) {
          AnnotationValue rhs = value.getValue();
          if (rhs != null) {
            return value.getKey().getSimpleName() + "=" + rhs;
          }
          return value.getKey().getSimpleName().toString();
        }
      };

  public static String printAnnotations(Collection<? extends AnnotationMirror> allAnnotations) {
    if (allAnnotations.isEmpty()) {
      return "";
    }

    SortedSet<String> converted = Sets.newTreeSet();
    for (AnnotationMirror annotation : allAnnotations) {
      converted.add(convertAnnotation(annotation));
    }

    return Joiner.on(" ").join(converted) + " ";
  }

  private static String convertAnnotation(AnnotationMirror mirror) {
    final StringBuilder builder = new StringBuilder();
    builder.append("@");
    builder.append(mirror.getAnnotationType());

    if (!mirror.getElementValues().isEmpty()) {
      builder.append('(');

      Iterable<String> annotationValues = Iterables.transform(mirror.getElementValues().entrySet(), ANNOTATION_VALUE_TO_STRING);
      Joiner.on(", ").appendTo(builder, Sets.newTreeSet(annotationValues));

      builder.append(')');
    }

    return builder.toString();
  }
}
