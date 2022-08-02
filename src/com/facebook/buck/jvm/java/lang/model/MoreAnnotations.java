/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.lang.model;

import com.sun.tools.javac.code.Symbol;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;

/** Helper methods for working with annotations. */
public class MoreAnnotations {

  /**
   * Get all type annotations attached to an element.
   *
   * <p>NOTE: this method should not be used outside of {@code ElementsExtendedImpl}. It uses
   * internal compiler APIs to fetch type annotations of javac Elements <b>in the right order.</b>
   * This code should be compatible with up to JDK 19, but further compiler upgrades may require
   * revising this code.
   */
  public static List<? extends AnnotationMirror> getAllTypeAnnotations(Element element) {
    if (element instanceof Symbol) {
      Symbol sym = (Symbol) element;
      return sym.getRawTypeAttributes();
    }

    return List.of();
  }
}
