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

import com.facebook.buck.jvm.java.lang.model.MoreElements;
import java.util.Objects;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleElementVisitor8;
import javax.lang.model.util.SimpleTypeVisitor8;
import org.objectweb.asm.Type;

/** Computes type descriptors from {@link Element}s or {@link TypeMirror}s. */
class DescriptorFactory {
  private final Elements elements;

  public DescriptorFactory(Elements elements) {
    this.elements = elements;
  }

  public String getDescriptor(TypeMirror type) {
    return getType(type).getDescriptor();
  }

  @Nullable
  public String getDescriptor(Element element) {
    ElementKind kind = element.getKind();
    if (kind.isClass() || kind.isInterface()) {
      return null;
    }
    return getType(element).getDescriptor();
  }

  private Type getType(Element element) {
    return Objects.requireNonNull(
        element.accept(
            new SimpleElementVisitor8<Type, Void>() {
              @Override
              protected Type defaultAction(Element e, Void aVoid) {
                throw new IllegalArgumentException(
                    String.format("Unexpected element kind: %s", element.getKind()));
              }

              @Override
              public Type visitExecutable(ExecutableElement e, Void aVoid) {
                Stream<TypeMirror> parameterTypeStream =
                    e.getParameters().stream().map(Element::asType);

                if (MoreElements.isInnerClassConstructor(e)) {
                  parameterTypeStream =
                      Stream.concat(
                          Stream.of(MoreElements.getOuterClass(e).asType()), parameterTypeStream);
                }

                return Type.getMethodType(
                    getType(e.getReturnType()),
                    parameterTypeStream
                        .map(DescriptorFactory.this::getType)
                        .toArray(size -> new Type[size]));
              }

              @Override
              public Type visitVariable(VariableElement e, Void aVoid) {
                return getType(e.asType());
              }
            },
            null));
  }

  public Type getType(TypeMirror typeMirror) {
    return Objects.requireNonNull(
        typeMirror.accept(
            new SimpleTypeVisitor8<Type, Void>() {
              @Override
              protected Type defaultAction(TypeMirror t, Void aVoid) {
                throw new IllegalArgumentException(
                    String.format("Unexpected type kind: %s", t.getKind()));
              }

              @Override
              public Type visitExecutable(ExecutableType e, Void aVoid) {
                Stream<? extends TypeMirror> parameterTypeStream = e.getParameterTypes().stream();

                return Type.getMethodType(
                    getType(e.getReturnType()),
                    parameterTypeStream
                        .map(DescriptorFactory.this::getType)
                        .toArray(size -> new Type[size]));
              }

              @Override
              public Type visitPrimitive(PrimitiveType type, Void aVoid) {
                switch (type.getKind()) {
                  case BOOLEAN:
                    return Type.BOOLEAN_TYPE;
                  case BYTE:
                    return Type.BYTE_TYPE;
                  case CHAR:
                    return Type.CHAR_TYPE;
                  case SHORT:
                    return Type.SHORT_TYPE;
                  case INT:
                    return Type.INT_TYPE;
                  case LONG:
                    return Type.LONG_TYPE;
                  case FLOAT:
                    return Type.FLOAT_TYPE;
                  case DOUBLE:
                    return Type.DOUBLE_TYPE;
                    // $CASES-OMITTED$
                  default:
                    throw new IllegalArgumentException(
                        String.format("Unexpected type kind: %s", type.getKind()));
                }
              }

              @Override
              public Type visitNoType(NoType t, Void aVoid) {
                if (t.getKind() != TypeKind.VOID) {
                  throw new IllegalArgumentException(
                      String.format("Unexpected type kind: %s", t.getKind()));
                }

                return Type.VOID_TYPE;
              }

              @Override
              public Type visitArray(ArrayType t, Void aVoid) {
                return Type.getObjectType("[" + getDescriptor(t.getComponentType()));
              }

              @Override
              public Type visitDeclared(DeclaredType t, Void aVoid) {
                // The erasure of a parameterized type is just the unparameterized version (JLS8
                // 4.6)
                TypeElement typeElement = (TypeElement) t.asElement();

                if (typeElement.getQualifiedName().contentEquals("")) {
                  // javac 7 uses a DeclaredType to model an intersection type
                  if (typeElement.getKind() == ElementKind.INTERFACE) {
                    return getType(typeElement.getInterfaces().get(0));
                  }

                  return getType(typeElement.getSuperclass());
                }

                return Type.getObjectType(getInternalName(typeElement));
              }

              @Override
              public Type visitError(ErrorType t, Void aVoid) {
                // If there's an ErrorType, compilation is going to fail anyway, so it doesn't
                // matter what we return.
                return Type.getObjectType("java/lang/Object");
              }

              @Override
              public Type visitTypeVariable(TypeVariable t, Void aVoid) {
                // The erasure of a type variable is the erasure of its leftmost bound (JLS8 4.6)
                // If there's only one bound, getUpperBound returns it directly; if there's more
                // than
                // one it returns an IntersectionType
                return getType(t.getUpperBound());
              }

              @Override
              public Type visitIntersection(IntersectionType t, Void aVoid) {
                // The erasure of a type variable is the erasure of its leftmost bound (JLS8 4.6)
                return getType(t.getBounds().get(0));
              }
            },
            null));
  }

  /** Gets the internal form of the binary name; see JVMS8 4.2.1 */
  public String getInternalName(TypeMirror typeMirror) {
    return getType(typeMirror).getInternalName();
  }

  /** Gets the internal form of the binary name; see JVMS8 4.2.1 */
  public String getInternalName(TypeElement typeElement) {
    return elements.getBinaryName(typeElement).toString().replace('.', '/');
  }
}
