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

import com.facebook.buck.util.liteinfersupport.Nullable;
import java.util.Arrays;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;

/**
 * An implementation of {@link Types} using just the AST of a single module, without its
 * dependencies. Of necessity, such an implementation will need to make assumptions about the
 * meanings of some names, and thus must be used with care. See documentation for individual methods
 * and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
class TreeBackedTypes implements Types {
  private final Types javacTypes;

  TreeBackedTypes(Types javacTypes) {
    this.javacTypes = javacTypes;
  }

  @Override
  @Nullable
  public Element asElement(TypeMirror t) {
    if (t.getKind() == TypeKind.DECLARED) {
      return ((DeclaredType) t).asElement();
    } else if (t.getKind() == TypeKind.TYPEVAR) {
      throw new UnsupportedOperationException("Type variables not yet implemented");
    }

    return null;
  }

  @Override
  public boolean isSameType(TypeMirror t1, TypeMirror t2) {
    boolean t1Artificial = isArtificialType(t1);
    boolean t2Artificial = isArtificialType(t2);

    if (!t1Artificial && !t2Artificial) {
      return javacTypes.isSameType(t1, t2);
    }

    // IMPORTANT: We can't early-out on reference equality, because wildcard types are never
    // considered the same type as one another.

    if (t1Artificial != t2Artificial || t1.getKind() != t2.getKind()) {
      return false;
    }

    assert t1Artificial && t2Artificial;

    switch (t1.getKind()) {
      case BOOLEAN:
      case BYTE:
      case CHAR:
      case SHORT:
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
      case NULL:
        return true;
      case WILDCARD:
        return false; // Wildcard types are never the same as one another; see docs
      case DECLARED:
        return isSameDeclaredType((DeclaredType) t1, (DeclaredType) t2);
      case TYPEVAR:
        return isSameTypeVariable((TypeVariable) t1, (TypeVariable) t2);
      case INTERSECTION:
        return isSameIntersectionType((IntersectionType) t1, (IntersectionType) t2);
      case ARRAY:
        return isSameType(((ArrayType) t1).getComponentType(), ((ArrayType) t2).getComponentType());
        // $CASES-OMITTED$
      default:
        throw new UnsupportedOperationException(
            String.format("isSameType NYI for kind %s", t1.getKind()));
    }
  }

  private boolean isSameDeclaredType(DeclaredType t1, DeclaredType t2) {
    if (!t1.asElement().equals(t2.asElement())) {
      return false;
    }

    List<? extends TypeMirror> args1 = t1.getTypeArguments();
    List<? extends TypeMirror> args2 = t2.getTypeArguments();
    if (args1.size() != args2.size()) {
      // TODO(jkeljo): This is impossible in code that will compile, but we will eventually need
      // to gracefully error out on code with some kinds of errors (such as providing the
      // wrong number of type arguments). Whenever we do that work, the body of this statement
      // should throw.
      return false;
    }

    for (int i = 0; i < args1.size(); i++) {
      if (!isSameType(args1.get(i), args2.get(i))) {
        return false;
      }
    }

    return true;
  }

  private boolean isSameTypeVariable(TypeVariable t1, TypeVariable t2) {
    if (!t1.asElement().equals(t2.asElement())) {
      return false;
    }

    // TODO(jkeljo): Upper and lower bounds could also matter if the type variable is the result of
    // capture conversion, but we don't support capture conversion yet (or maybe ever).

    return true;
  }

  private boolean isSameIntersectionType(IntersectionType t1, IntersectionType t2) {
    List<? extends TypeMirror> t1Bounds = t1.getBounds();
    List<? extends TypeMirror> t2Bounds = t2.getBounds();
    if (t1Bounds.size() != t2Bounds.size()) {
      return false;
    }

    for (TypeMirror t1Bound : t1Bounds) {
      if (!listContainsType(t2Bounds, t1Bound)) {
        return false;
      }
    }

    return true;
  }

  private boolean listContainsType(List<? extends TypeMirror> list, TypeMirror type) {
    boolean found = false;
    for (TypeMirror t2Bound : list) {
      if (isSameType(type, t2Bound)) {
        found = true;
        break;
      }
    }
    return found;
  }

  @Override
  public boolean isSubtype(TypeMirror t1, TypeMirror t2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isAssignable(TypeMirror t1, TypeMirror t2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean contains(TypeMirror t1, TypeMirror t2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSubsignature(ExecutableType m1, ExecutableType m2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<? extends TypeMirror> directSupertypes(TypeMirror t) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypeMirror erasure(TypeMirror t) {
    if (isArtificialType(t)) {
      return erasure((StandaloneTypeMirror) t);
    }

    return javacTypes.erasure(t);
  }

  private TypeMirror erasure(StandaloneTypeMirror t) {
    switch (t.getKind()) {
      case ARRAY:
        {
          ArrayType arrayType = (ArrayType) t;
          return getArrayType(erasure(arrayType.getComponentType()));
        }
      case DECLARED:
        {
          DeclaredType declaredType = (DeclaredType) t;
          TypeElement typeElement = (TypeElement) declaredType.asElement();
          if (declaredType.getEnclosingType().getKind() == TypeKind.DECLARED) {
            DeclaredType enclosingType = (DeclaredType) declaredType.getEnclosingType();

            return getDeclaredType((DeclaredType) erasure(enclosingType), typeElement);
          }

          return getDeclaredType(typeElement);
        }
        // $CASES-OMITTED$
      default:
        throw new UnsupportedOperationException();
    }
  }

  @Override
  public TypeElement boxedClass(PrimitiveType p) {
    throw new UnsupportedOperationException();
  }

  @Override
  public PrimitiveType unboxedType(TypeMirror t) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypeMirror capture(TypeMirror t) {
    throw new UnsupportedOperationException();
  }

  @Override
  public PrimitiveType getPrimitiveType(TypeKind kind) {
    return javacTypes.getPrimitiveType(kind);
  }

  @Override
  public NullType getNullType() {
    return javacTypes.getNullType();
  }

  @Override
  public NoType getNoType(TypeKind kind) {
    return javacTypes.getNoType(kind);
  }

  /* package */ StandalonePackageType getPackageType(ArtificialPackageElement element) {
    return new StandalonePackageType(element);
  }

  @Override
  public ArrayType getArrayType(TypeMirror componentType) {
    if (isArtificialType(componentType)) {
      return new StandaloneArrayType(componentType);
    }

    return javacTypes.getArrayType(componentType);
  }

  @Override
  public WildcardType getWildcardType(
      @Nullable TypeMirror extendsBound, @Nullable TypeMirror superBound) {
    if (containsArtificialTypes(extendsBound, superBound)) {
      return new StandaloneWildcardType(extendsBound, superBound);
    }

    return javacTypes.getWildcardType(extendsBound, superBound);
  }

  @Override
  public DeclaredType getDeclaredType(TypeElement typeElem, TypeMirror... typeArgs) {
    return getDeclaredType(null, typeElem, typeArgs);
  }

  @Override
  public DeclaredType getDeclaredType(
      @Nullable DeclaredType containing, TypeElement typeElem, TypeMirror... typeArgs) {
    if (isArtificialType(containing)
        || isArtificialElement(typeElem)
        || containsArtificialTypes(typeArgs)) {
      if (containing != null) {
        return new StandaloneDeclaredType(typeElem, Arrays.asList(typeArgs), containing);
      } else {
        return new StandaloneDeclaredType(this, typeElem, Arrays.asList(typeArgs));
      }
    }

    return javacTypes.getDeclaredType(containing, typeElem, typeArgs);
  }

  private boolean isArtificialElement(Element element) {
    return element instanceof ArtificialElement;
  }

  private boolean isArtificialType(@Nullable TypeMirror typeMirror) {
    return typeMirror instanceof StandaloneTypeMirror;
  }

  private boolean containsArtificialTypes(TypeMirror... typeMirrors) {
    for (TypeMirror typeMirror : typeMirrors) {
      if (isArtificialType(typeMirror)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public TypeMirror asMemberOf(DeclaredType containing, Element element) {
    throw new UnsupportedOperationException();
  }
}
