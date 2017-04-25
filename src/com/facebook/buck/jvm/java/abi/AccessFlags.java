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

package com.facebook.buck.jvm.java.abi;

import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import org.objectweb.asm.Opcodes;

/** Computes the access flags (see JVMS8 4.1, 4.5, 4.6) for {@link Element}s. */
public final class AccessFlags {
  private AccessFlags() {}

  /**
   * Gets the class access flags (see JVMS8 4.1) for the given type element, augmented by the
   * special ASM pseudo-access flag for @Deprecated types.
   */
  public static int getAccessFlags(TypeElement typeElement) {
    int result = getCommonAccessFlags(typeElement);

    switch (typeElement.getKind()) {
      case ANNOTATION_TYPE:
        // No ACC_SUPER here per JVMS 4.1
        result = result | Opcodes.ACC_ANNOTATION;
        result = result | Opcodes.ACC_INTERFACE;
        result = result | Opcodes.ACC_ABSTRACT;
        break;
      case ENUM:
        result = result | Opcodes.ACC_SUPER; // JVMS 4.1
        result = result | Opcodes.ACC_ENUM;

        // Enums have this lovely property that they can have abstract members without themselves
        // having to be declared abstract
        for (Element enclosed : typeElement.getEnclosedElements()) {
          if (enclosed.getModifiers().contains(Modifier.ABSTRACT)) {
            result = result | Opcodes.ACC_ABSTRACT;
            break;
          }
        }
        break;
      case INTERFACE:
        // No ACC_SUPER here per JVMS 4.1
        result = result | Opcodes.ACC_ABSTRACT;
        result = result | Opcodes.ACC_INTERFACE;
        break;
        // $CASES-OMITTED$
      default:
        result = result | Opcodes.ACC_SUPER; // JVMS 4.1
        break;
    }

    return result;
  }

  /**
   * Gets the method access flags (see JVMS8 4.6) for the given executable element, augmented by the
   * special ASM pseudo-access flag for @Deprecated methods.
   */
  public static int getAccessFlags(ExecutableElement executableElement) {
    int result = getCommonAccessFlags(executableElement);

    if (executableElement.isVarArgs()) {
      result = result | Opcodes.ACC_VARARGS;
    }

    return result;
  }

  /**
   * Gets the field access flags (see JVMS8 4.5) for the given variable element, augmented by the
   * special ASM pseudo-access flag for @Deprecated fields.
   */
  public static int getAccessFlags(VariableElement variableElement) {
    int result = getCommonAccessFlags(variableElement);

    if (variableElement.getKind() == ElementKind.ENUM_CONSTANT) {
      result = result | Opcodes.ACC_ENUM;
    }

    return result;
  }

  /**
   * Gets the access flags (see JVMS8 4.1, 4.5, 4.6) for the given element, from among those that
   * are common to all kinds of elements.
   */
  private static int getCommonAccessFlags(Element element) {
    int result = modifiersToAccessFlags(element.getModifiers());

    if (isDeprecated(element)) {
      result = result | Opcodes.ACC_DEPRECATED;
    }

    return result;
  }

  private static boolean isDeprecated(Element element) {
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      TypeElement annotationTypeElement = (TypeElement) annotationType.asElement();

      // Note: We can't use Types.isSameType against a type obtained from
      // Elements.getTypeElement().asType() here because it appears that getTypeElement actually
      // returns a different element for some fundamental classes than is actually used for
      // compilation.
      if (annotationTypeElement.getQualifiedName().contentEquals("java.lang.Deprecated")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Gets the access flags (see JVMS8 4.1, 4.5, 4.6) corresponding to the given set of modifiers.
   */
  private static int modifiersToAccessFlags(Set<Modifier> modifiers) {
    int result = 0;
    for (Modifier modifier : modifiers) {
      result = result | modifierToAccessFlag(modifier);
    }
    return result;
  }

  /** Gets the access flag (see JVMS8 4.1, 4.5, 4.6) corresponding to the given modifier. */
  private static int modifierToAccessFlag(Modifier modifier) {
    switch (modifier) {
      case PUBLIC:
        return Opcodes.ACC_PUBLIC;
      case PROTECTED:
        return Opcodes.ACC_PROTECTED;
      case PRIVATE:
        return Opcodes.ACC_PRIVATE;
      case ABSTRACT:
        return Opcodes.ACC_ABSTRACT;
      case DEFAULT:
        return 0;
      case STATIC:
        return Opcodes.ACC_STATIC;
      case FINAL:
        return Opcodes.ACC_FINAL;
      case TRANSIENT:
        return Opcodes.ACC_TRANSIENT;
      case VOLATILE:
        return Opcodes.ACC_VOLATILE;
      case SYNCHRONIZED:
        return Opcodes.ACC_SYNCHRONIZED;
      case NATIVE:
        return Opcodes.ACC_NATIVE;
      case STRICTFP:
        return Opcodes.ACC_STRICT;
      default:
        throw new IllegalArgumentException(String.format("Unexpected modifier: %s", modifier));
    }
  }
}
