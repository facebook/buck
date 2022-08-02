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

package com.facebook.buck.jvm.java.lang.extra;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.TypeAnnotationPosition;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;

/**
 * Helper methods for working with type annotations.
 *
 * <p>NOTE 1: This class is separate from {@code MoreAnnotations} because the latter is in the
 * bootstrapper where we cannot depend on ASM which is required here.
 *
 * <p>NOTE 2: This code uses internal compiler APIs to extra a type position of a javac type
 * annotation. It should be compatible with up to JDK 19 but further updates may require revising
 * this code.
 */
public class MoreTypeAnnotations {

  /** Extract and re-encode annotation's position type as ASM's {@link TypeReference}. */
  @Nullable
  public static TypeReference getAnnotationTypeReference(AnnotationMirror annotation) {
    TypeAnnotationPosition position = getAnnotationPosition(annotation);
    if (position == null) {
      return null;
    }

    switch (position.type) {
      case CLASS_TYPE_PARAMETER:
        return TypeReference.newTypeParameterReference(
            TypeReference.CLASS_TYPE_PARAMETER, position.parameter_index);
      case METHOD_TYPE_PARAMETER:
        return TypeReference.newTypeParameterReference(
            TypeReference.METHOD_TYPE_PARAMETER, position.parameter_index);
      case CLASS_EXTENDS:
        return TypeReference.newSuperTypeReference(position.type_index);
      case CLASS_TYPE_PARAMETER_BOUND:
        return TypeReference.newTypeParameterBoundReference(
            TypeReference.CLASS_TYPE_PARAMETER_BOUND,
            position.parameter_index,
            position.bound_index);
      case METHOD_TYPE_PARAMETER_BOUND:
        return TypeReference.newTypeParameterBoundReference(
            TypeReference.METHOD_TYPE_PARAMETER_BOUND,
            position.parameter_index,
            position.bound_index);
      case FIELD:
        return TypeReference.newTypeReference(TypeReference.FIELD);
      case METHOD_RETURN:
        return TypeReference.newTypeReference(TypeReference.METHOD_RETURN);
      case METHOD_RECEIVER:
        return TypeReference.newTypeReference(TypeReference.METHOD_RECEIVER);
      case METHOD_FORMAL_PARAMETER:
        return TypeReference.newFormalParameterReference(position.parameter_index);
        // $CASES-OMITTED$
      default:
        // We're not interested in other types of annotations to generate an ABI
        return null;
    }
  }

  /** Extract and re-encode annotation's location as ASM's {@link TypePath}. */
  @Nullable
  public static TypePath getAnnotationTypePath(AnnotationMirror annotation) {
    TypeAnnotationPosition position = getAnnotationPosition(annotation);
    if (position == null) {
      return null;
    }

    if (position.location.isEmpty()) {
      return null;
    }

    int encodingLength =
        1 + position.location.size() * TypeAnnotationPosition.TypePathEntry.bytesPerEntry;
    byte[] encodedPath = new byte[encodingLength];
    encodedPath[0] = (byte) position.location.size();
    List<Integer> binaryLocation = TypeAnnotationPosition.getBinaryFromTypePath(position.location);
    for (int i = 0; i < binaryLocation.size(); ++i) {
      encodedPath[i + 1] = binaryLocation.get(i).byteValue();
    }

    return createTypePath(encodedPath);
  }

  @Nullable
  private static TypeAnnotationPosition getAnnotationPosition(AnnotationMirror annotation) {
    if (!(annotation instanceof Attribute.Compound)) {
      return null;
    }

    return ((Attribute.Compound) annotation).getPosition();
  }

  /**
   * NOTE: this method is needed because {@link TypePath} doesn't have a public constructor but is
   * required for visiting type annotations. Reflection seems the only way to build an instance.
   *
   * @param encoded binary encoded representation of a type path
   * @return a type path
   */
  private static TypePath createTypePath(byte[] encoded) {
    try {
      Constructor<TypePath> ctor = TypePath.class.getDeclaredConstructor(byte[].class, int.class);
      ctor.setAccessible(true);
      return ctor.newInstance(encoded, 0);
    } catch (NoSuchMethodException
        | InvocationTargetException
        | InstantiationException
        | IllegalAccessException e) {
      return null;
    }
  }
}
