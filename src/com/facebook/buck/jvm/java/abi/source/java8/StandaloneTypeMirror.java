/*
 * Copyright 2016-present Facebook, Inc.
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;

/**
 * An implementation of {@link TypeMirror} that is not dependent on any particular compiler
 * implementation. Subclasses may require {@link javax.lang.model.element.Element}s and/or {@link
 * javax.lang.model.type.TypeMirror}s, but do not depend on any particular implementation of them
 * (beyond the spec).
 *
 * <p>NOTE: A Java10 copy of this file exists in ../java10/StandaloneTypeMirror.java. Please make
 * sure to update the other copy when modifying this file.
 */
abstract class StandaloneTypeMirror implements TypeMirror {
  private final TypeKind kind;
  private final List<? extends AnnotationMirror> annotations;

  protected StandaloneTypeMirror(TypeKind kind) {
    this(kind, Collections.emptyList());
  }

  protected StandaloneTypeMirror(TypeKind kind, List<? extends AnnotationMirror> annotations) {
    this.kind = kind;
    this.annotations = Collections.unmodifiableList(new ArrayList<>(annotations));
  }

  @Override
  public TypeKind getKind() {
    return kind;
  }

  @Override
  public <R, P> R accept(TypeVisitor<R, P> v, P p) {
    switch (kind) {
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case CHAR:
      case FLOAT:
      case DOUBLE:
        return v.visitPrimitive((PrimitiveType) this, p);
      case PACKAGE:
      case VOID:
      case NONE:
        return v.visitNoType((NoType) this, p);
      case NULL:
        return v.visitNull((NullType) this, p);
      case ARRAY:
        return v.visitArray((ArrayType) this, p);
      case DECLARED:
        return v.visitDeclared((DeclaredType) this, p);
      case ERROR:
        return v.visitError((ErrorType) this, p);
      case TYPEVAR:
        return v.visitTypeVariable((TypeVariable) this, p);
      case WILDCARD:
        return v.visitWildcard((WildcardType) this, p);
      case EXECUTABLE:
        return v.visitExecutable((ExecutableType) this, p);
      case OTHER:
        return v.visit(this, p);
      case UNION:
        return v.visitUnion((UnionType) this, p);
      case INTERSECTION:
        return v.visitIntersection((IntersectionType) this, p);
      default:
        throw new AssertionError(String.format("Unknown TypeKind: %s", kind));
    }
  }

  @Override
  public List<? extends AnnotationMirror> getAnnotationMirrors() {
    return this.annotations;
  }

  @Override
  public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
    // This one is really difficult to implement, and also generally a bad thing to use anyway
    throw new UnsupportedOperationException();
  }

  @Override
  public <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
    // This one is really difficult to implement, and also generally a bad thing to use anyway
    throw new UnsupportedOperationException();
  }
}
