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

import java.util.Collection;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;

class Annotations {

  /** Utility class: do not instantiate. */
  private Annotations() {}

  public static String printAnnotations(Collection<? extends AnnotationMirror> allAnnotations) {
    if (allAnnotations.isEmpty()) {
      return "";
    }

    SortedSet<String> converted = new TreeSet<>();
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

      SortedSet<String> annotationValues = new TreeSet<>();
      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
          mirror.getElementValues().entrySet()) {
        AnnotationValue rhs = entry.getValue();

        if (rhs != null) {
          annotationValues.add(entry.getKey().getSimpleName() + "=" + rhs);
          continue;
        }
        annotationValues.add(entry.getKey().getSimpleName().toString());
      }
      Joiner.on(", ").appendTo(builder, new TreeSet<>(annotationValues));

      builder.append(')');
    }

    return builder.toString();
  }
}
