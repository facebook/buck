/*
 * Copyright 2015-present Facebook, Inc.
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

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

class AnnotationValueMirror extends AnnotationVisitor {
  @Nullable private final Object primitiveValue;
  @Nullable private final EnumMirror enumValue;
  @Nullable private final AnnotationMirror annotationValue;
  @Nullable private final List<AnnotationValueMirror> arrayValue;

  public static AnnotationValueMirror forArray() {
    return new AnnotationValueMirror();
  }

  public static AnnotationValueMirror forAnnotation(AnnotationMirror annotation) {
    return new AnnotationValueMirror(annotation);
  }

  public static AnnotationValueMirror forEnum(String desc, String value) {
    return new AnnotationValueMirror(new EnumMirror(desc, value));
  }

  public static AnnotationValueMirror forPrimitive(Object value) {
    return new AnnotationValueMirror(value);
  }

  private AnnotationValueMirror(Object primitiveValue) {
    super(Opcodes.ASM5);
    this.primitiveValue = primitiveValue;
    this.enumValue = null;
    this.annotationValue = null;
    this.arrayValue = null;
  }

  private AnnotationValueMirror(EnumMirror enumValue) {
    super(Opcodes.ASM5);
    this.primitiveValue = null;
    this.enumValue = enumValue;
    this.annotationValue = null;
    this.arrayValue = null;
  }

  private AnnotationValueMirror(AnnotationMirror annotationValue) {
    super(Opcodes.ASM5);
    this.primitiveValue = null;
    this.enumValue = null;
    this.annotationValue = annotationValue;
    this.arrayValue = null;
  }

  private AnnotationValueMirror() {
    super(Opcodes.ASM5);
    this.primitiveValue = null;
    this.enumValue = null;
    this.annotationValue = null;
    this.arrayValue = new ArrayList<>();
  }

  public void accept(@Nullable String name, AnnotationVisitor visitor) {
    if (primitiveValue != null) {
      visitor.visit(name, primitiveValue);
    } else if (enumValue != null) {
      visitor.visitEnum(name, enumValue.desc, enumValue.value);
    } else if (annotationValue != null) {
      annotationValue.appendTo(visitor, name);
    } else if (arrayValue != null) {
      AnnotationVisitor arrayVisitor = visitor.visitArray(name);

      for (AnnotationValueMirror element : arrayValue) {
        element.accept(null, arrayVisitor);
      }

      arrayVisitor.visitEnd();
    }
  }

  @Override
  public AnnotationVisitor visitArray(String name) {
    throw new AssertionError("Annotation values cannot be nested arrays.");
  }

  @Override
  public void visit(@Nullable String name, Object value) {
    if (arrayValue == null) {
      throw new AssertionError("Calling visit makes no sense on a non-array AnnotationValue.");
    }
    if (!(value instanceof String) && !(value instanceof Type)) {
      throw new AssertionError("ASM is expected to provide primitive arrays as simple values.");
    }

    arrayValue.add(AnnotationValueMirror.forPrimitive(value));
  }

  @Override
  public void visitEnum(@Nullable String name, String desc, String value) {
    if (arrayValue == null) {
      throw new AssertionError("Calling visitEnum makes no sense on a non-array AnnotationValue.");
    }
    if (name != null) {
      throw new AssertionError("Expected name == null");
    }

    arrayValue.add(AnnotationValueMirror.forEnum(desc, value));
  }

  @Override
  public AnnotationVisitor visitAnnotation(@Nullable String name, String desc) {
    if (arrayValue == null) {
      throw new AssertionError(
          "Calling visitAnnotation makes no sense on a non-array AnnotationValue.");
    }
    if (name != null) {
      throw new AssertionError("Expected name == null");
    }

    AnnotationMirror annotation = new AnnotationMirror(desc, true);
    arrayValue.add(AnnotationValueMirror.forAnnotation(annotation));
    return annotation;  // Caller will call visit methods on this to provide the annotation args
  }
}
