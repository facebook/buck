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

import com.facebook.buck.jvm.java.abi.source.api.CannotInferException;
import com.facebook.buck.jvm.java.lang.model.ElementsExtended;
import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import org.objectweb.asm.Opcodes;

/** Computes the access flags (see JVMS8 4.1, 4.5, 4.6) for {@link Element}s. */
public final class AccessFlags {
  private final ElementsExtended elements;

  public AccessFlags(ElementsExtended elements) {
    this.elements = elements;
  }

  /**
   * Gets the class access flags (see JVMS8 4.1) for the given type element as they should appear in
   * the ClassNode of a class file. Inner-class specific flags are not allowed in that node,
   * presumably for compatibility reasons.
   */
  public int getAccessFlagsForClassNode(TypeElement e) {
    // Static never makes it into the file for classes
    int accessFlags = getAccessFlags(e) & ~Opcodes.ACC_STATIC;
    if (e.getNestingKind() != NestingKind.TOP_LEVEL) {
      if (e.getModifiers().contains(Modifier.PROTECTED)) {
        // It looks like inner classes with protected visibility get marked as public, and then
        // their InnerClasses attributes override that more specifically
        accessFlags = (accessFlags & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
      } else if (e.getModifiers().contains(Modifier.PRIVATE)) {
        // It looks like inner classes with private visibility get marked as package, and then
        // their InnerClasses attributes override that more specifically
        accessFlags = (accessFlags & ~Opcodes.ACC_PRIVATE);
      }
    }

    return accessFlags;
  }

  /**
   * Gets the class access flags (see JVMS8 4.1) for the given type element, augmented by the
   * special ASM pseudo-access flag for @Deprecated types.
   */
  public int getAccessFlags(TypeElement typeElement) {
    ElementKind kind;
    try {
      kind = typeElement.getKind();
    } catch (CannotInferException e) {
      Preconditions.checkState(typeElement.getNestingKind().isNested());

      // We cannot know the access flags of an inferred type element. However, the only
      // flag that matters in the InnerClasses table is ACC_STATIC. When reading the
      // InnerClasses table, the compiler may create ClassSymbols for types it hasn't
      // seen before, and the absence of ACC_STATIC will cause it to mark those
      // ClassSymbols as inner classes, and it will not correct that when later loading
      // the class from its definitive class file. However, it is safe to mark
      // everything with ACC_STATIC, because the compiler *will* properly update
      // non-static classes when loading their definitive class files.
      // (http://hg.openjdk.java.net/jdk8u/jdk8u/langtools/file/9986bf97a48d/src/share/classes/com/sun/tools/javac/jvm/ClassReader.java#l2272)
      return Opcodes.ACC_STATIC;
    }

    int result = getCommonAccessFlags(typeElement);
    switch (kind) {
      case ANNOTATION_TYPE:
        // No ACC_SUPER here per JVMS 4.1
        result = result | Opcodes.ACC_ANNOTATION;
        result = result | Opcodes.ACC_INTERFACE;
        result = result | Opcodes.ACC_ABSTRACT;
        break;
      case ENUM:
        result = result | Opcodes.ACC_SUPER; // JVMS 4.1
        result = result | Opcodes.ACC_ENUM;

        if (isAbstractEnum(typeElement)) {
          result = result & ~Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT;
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

  private boolean isAbstractEnum(TypeElement typeElement) {
    List<ExecutableElement> methods = ElementFilter.methodsIn(elements.getAllMembers(typeElement));

    return methods.stream()
        .filter(
            it ->
                !isOverridden(
                    it, elements.getAllMethods(typeElement, it.getSimpleName()), typeElement))
        .anyMatch(it -> it.getModifiers().contains(Modifier.ABSTRACT));
  }

  private boolean isOverridden(
      ExecutableElement overridden, List<ExecutableElement> methods, TypeElement inType) {
    for (ExecutableElement overriding : methods) {
      if (elements.overrides(overriding, overridden, inType)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Gets the method access flags (see JVMS8 4.6) for the given executable element, augmented by the
   * special ASM pseudo-access flag for @Deprecated methods.
   */
  public int getAccessFlags(ExecutableElement executableElement) {
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
  public int getAccessFlags(VariableElement variableElement) {
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
  private int getCommonAccessFlags(Element element) {
    try {
      int result = modifiersToAccessFlags(element.getModifiers());

      if (elements.isDeprecated(element)) {
        result = result | Opcodes.ACC_DEPRECATED;
      }

      return result;
    } catch (CannotInferException e) {
      // We should never call this method with an inferred type.
      throw new AssertionError("Unexpected call to getAccessFlags with an inferred type.", e);
    }
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
