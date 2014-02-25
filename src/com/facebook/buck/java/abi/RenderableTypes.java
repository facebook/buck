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

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

class RenderableTypes {

  public Renderable deriveFor(Element element) {
    if (element.getModifiers().contains(Modifier.PRIVATE)) {
      return new EmptySummary();
    }

    switch (element.getKind()) {
      case ANNOTATION_TYPE:
      case CLASS:
      case ENUM:
      case INTERFACE:
        return new TypeSummary(this, (TypeElement) element);

      case CONSTRUCTOR:
        return new ConstructorSummary((ExecutableElement) element);

      case ENUM_CONSTANT:
        return new EnumConstantSummary((VariableElement) element);

      case FIELD:
        return new FieldSummary((VariableElement) element);

      case METHOD:
        return new MethodSummary((ExecutableElement) element);

      // $CASES-OMITTED$
      default:
        throw new RuntimeException("Unknown kind: " + element.getKind());
    }
  }
}
