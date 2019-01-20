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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.SimpleElementVisitor8;
import javax.lang.model.util.SimpleTypeVisitor8;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

/** Computes type signatures for {@link Element}s that require them. */
class SignatureFactory {
  private final DescriptorFactory descriptorFactory;

  /**
   * Adapts a SignatureVisitor to the ElementVisitor contract, so we can drive it from an Element.
   */
  private final ElementVisitor<Void, SignatureVisitor> elementVisitorAdapter =
      new SimpleElementVisitor8<Void, SignatureVisitor>() {
        @Override
        protected Void defaultAction(Element e, SignatureVisitor signatureVisitor) {
          throw new IllegalArgumentException(
              String.format("Unexpected element kind: %s", e.getKind()));
        }

        @Override
        public Void visitType(TypeElement element, SignatureVisitor visitor) {
          if (!signatureRequired(element)) {
            return null;
          }

          for (TypeParameterElement typeParameterElement : element.getTypeParameters()) {
            typeParameterElement.accept(this, visitor);
          }

          TypeMirror superclass = element.getSuperclass();
          if (superclass.getKind() != TypeKind.NONE) {
            superclass.accept(typeVisitorAdapter, visitor.visitSuperclass());
          } else {
            // Interface type; implicit superclass of Object
            SignatureVisitor superclassVisitor = visitor.visitSuperclass();
            superclassVisitor.visitClassType("java/lang/Object");
            superclassVisitor.visitEnd();
          }

          for (TypeMirror interfaceType : element.getInterfaces()) {
            interfaceType.accept(typeVisitorAdapter, visitor.visitInterface());
          }

          return null;
        }

        @Override
        public Void visitTypeParameter(TypeParameterElement element, SignatureVisitor visitor) {
          visitor.visitFormalTypeParameter(element.getSimpleName().toString());
          for (TypeMirror boundType : element.getBounds()) {
            boolean isClass;
            try {
              if (boundType.getKind() == TypeKind.DECLARED) {
                isClass = ((DeclaredType) boundType).asElement().getKind().isClass();
              } else {
                isClass = boundType.getKind() == TypeKind.TYPEVAR;
              }
            } catch (CannotInferException e) {
              // We can't know whether an inferred type is a class or interface, but it turns out
              // the compiler does not distinguish between them when reading signatures, so we can
              // write inferred types as interface bounds. We go ahead and write all bounds as
              // interface bounds to make the SourceAbiCompatibleSignatureVisitor possible.
              isClass = false;
            }

            boundType.accept(
                typeVisitorAdapter,
                isClass ? visitor.visitClassBound() : visitor.visitInterfaceBound());
          }

          return null;
        }

        @Override
        public Void visitExecutable(ExecutableElement element, SignatureVisitor visitor) {
          return element.asType().accept(typeVisitorAdapter, visitor);
        }

        @Override
        public Void visitVariable(VariableElement element, SignatureVisitor visitor) {
          if (!signatureRequired(element)) {
            return null;
          }

          element.asType().accept(typeVisitorAdapter, visitor);
          return null;
        }
      };

  /**
   * Adapts a SignatureVisitor to the TypeVisitor contract, so we can drive it from a TypeMirror.
   */
  private final TypeVisitor<Void, SignatureVisitor> typeVisitorAdapter =
      new SimpleTypeVisitor8<Void, SignatureVisitor>() {
        @Override
        protected Void defaultAction(TypeMirror e, SignatureVisitor signatureVisitor) {
          throw new IllegalArgumentException(
              String.format("Unexpected type kind: %s", e.getKind()));
        }

        @Override
        public Void visitError(ErrorType t, SignatureVisitor visitor) {
          // We don't really know, but if there's an error type the compilation is going to fail
          // anyway, so just pretend it's Object.
          visitor.visitClassType("java/lang/Object");
          visitor.visitEnd();
          return null;
        }

        @Override
        public Void visitNoType(NoType t, SignatureVisitor visitor) {
          if (t.getKind() == TypeKind.VOID) {
            visitor.visitBaseType(descriptorFactory.getDescriptor(t).charAt(0));
            return null;
          }

          return defaultAction(t, visitor);
        }

        @Override
        public Void visitExecutable(ExecutableType t, SignatureVisitor visitor) {
          if (!signatureRequired(t)) {
            return null;
          }

          for (TypeVariable typeVariable : t.getTypeVariables()) {
            typeVariable.asElement().accept(elementVisitorAdapter, visitor);
          }

          for (TypeMirror parameter : t.getParameterTypes()) {
            parameter.accept(this, visitor.visitParameterType());
          }

          t.getReturnType().accept(this, visitor.visitReturnType());

          if (throwsATypeVar(t)) {
            for (TypeMirror thrownType : t.getThrownTypes()) {
              thrownType.accept(typeVisitorAdapter, visitor.visitExceptionType());
            }
          }

          return null;
        }

        @Override
        public Void visitPrimitive(PrimitiveType t, SignatureVisitor visitor) {
          visitor.visitBaseType(descriptorFactory.getDescriptor(t).charAt(0));
          return null;
        }

        @Override
        public Void visitTypeVariable(TypeVariable t, SignatureVisitor visitor) {
          visitor.visitTypeVariable(t.asElement().getSimpleName().toString());
          return null;
        }

        @Override
        public Void visitArray(ArrayType t, SignatureVisitor visitor) {
          t.getComponentType().accept(this, visitor.visitArrayType());
          return null;
        }

        @Override
        public Void visitWildcard(WildcardType t, SignatureVisitor visitor) {
          TypeMirror bound = t.getExtendsBound();
          if (bound != null) {
            bound.accept(this, visitor.visitTypeArgument(SignatureVisitor.EXTENDS));
            return null;
          }

          bound = t.getSuperBound();
          if (bound != null) {
            bound.accept(this, visitor.visitTypeArgument(SignatureVisitor.SUPER));
            return null;
          }

          visitor.visitTypeArgument();
          return null;
        }

        @Override
        public Void visitDeclared(DeclaredType t, SignatureVisitor visitor) {
          List<DeclaredType> enclosingTypes = findEnclosingTypes(t);

          // Signatures are weird with inner classes. They use $ as the separator until you
          // encounter a class with type arguments, then . as the separator thereafter.
          //
          // What this means for visiting a class type with a SignatureVisitor is that we don't
          // start the visiting (by calling visitClassType) at the top-level class; we start at the
          // outermost class with type arguments. Then we visit the type arguments, and then
          // every inner class from there to the type for which we're generating a signature.
          boolean calledVisitClassType = false;
          int numTypes = enclosingTypes.size();
          for (int i = 0; i < numTypes; i++) {
            DeclaredType type = enclosingTypes.get(i);
            List<? extends TypeMirror> typeArguments;
            try {
              typeArguments = type.getTypeArguments();
            } catch (CannotInferException e) {
              // InferredDeclaredTypes can only be obtained by calling asType on an
              // InferredTypeElement. Types that are used in signature generation are not obtained
              // in this manner, so they should always be StandaloneDeclaredTypes and this exception
              // should never happen.
              throw new AssertionError("Unexpected inferred type.", e);
            }
            if (calledVisitClassType) {
              visitor.visitInnerClassType(type.asElement().getSimpleName().toString());
            } else if (!typeArguments.isEmpty() || i == numTypes - 1) {
              visitor.visitClassType(descriptorFactory.getInternalName(type));
              calledVisitClassType = true;
            } else {
              // Keep looking for the outermost enclosing generic type
              continue;
            }

            for (TypeMirror typeArgument : typeArguments) {
              typeArgument.accept(this, visitor.visitTypeArgument(SignatureVisitor.INSTANCEOF));
            }
          }
          visitor.visitEnd();

          return null;
        }

        private List<DeclaredType> findEnclosingTypes(DeclaredType t) {
          List<DeclaredType> result = new ArrayList<>();

          TypeMirror walker = t;
          while (walker.getKind() != TypeKind.NONE) {
            result.add((DeclaredType) walker);
            walker = ((DeclaredType) walker).getEnclosingType();
          }
          Collections.reverse(result);

          return result;
        }
      };

  public SignatureFactory(DescriptorFactory descriptorFactory) {
    this.descriptorFactory = descriptorFactory;
  }

  /**
   * Returns the type signature of the given element. If none is required by the VM spec, returns
   * null.
   */
  @Nullable
  public String getSignature(Element element) {
    SignatureWriter writer = new SignatureWriter();
    element.accept(elementVisitorAdapter, writer);
    String result = writer.toString();
    return result.isEmpty() ? null : result;
  }

  @Nullable
  public String getSignature(TypeMirror type) {
    SignatureWriter writer = new SignatureWriter();
    type.accept(typeVisitorAdapter, writer);
    String result = writer.toString();
    return result.isEmpty() ? null : result;
  }

  /**
   * Returns true if the JVM spec requires a signature to be emitted for this type. See JVMS8
   * 4.7.9.1
   */
  private static boolean signatureRequired(TypeElement element) {
    if (!element.getTypeParameters().isEmpty()) {
      return true;
    }

    if (usesGenerics(element.getSuperclass())) {
      return true;
    }

    for (TypeMirror interfaceType : element.getInterfaces()) {
      if (usesGenerics(interfaceType)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Returns true if the JVM spec requires a signature to be emitted for this method. See JVMS8
   * 4.7.9.1
   */
  private static boolean signatureRequired(ExecutableType type) {
    if (!type.getTypeVariables().isEmpty()) {
      return true;
    }

    if (usesGenerics(type.getReturnType())) {
      return true;
    }

    for (TypeMirror parameterType : type.getParameterTypes()) {
      if (usesGenerics(parameterType)) {
        return true;
      }
    }

    return throwsATypeVar(type);
  }

  private static boolean throwsATypeVar(ExecutableType type) {
    for (TypeMirror thrownType : type.getThrownTypes()) {
      if (thrownType.getKind() == TypeKind.TYPEVAR) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns true if the JVM spec requires a signature to be emitted for this field. See JVMS8
   * 4.7.9.1
   */
  private static boolean signatureRequired(VariableElement element) {
    return usesGenerics(element.asType());
  }

  private static boolean usesGenerics(TypeMirror type) {
    return type.accept(
        new SimpleTypeVisitor8<Boolean, Void>() {
          @Override
          protected Boolean defaultAction(TypeMirror e, Void aVoid) {
            throw new IllegalArgumentException(
                String.format("Unexpected type kind: %s", e.getKind()));
          }

          @Override
          public Boolean visitError(ErrorType t, Void aVoid) {
            // We don't really know, but if there's an ErrorType it means compilation is going to
            // fail anyway so it doesn't matter.
            return false;
          }

          @Override
          public Boolean visitPrimitive(PrimitiveType t, Void aVoid) {
            return false;
          }

          @Override
          public Boolean visitTypeVariable(TypeVariable t, Void aVoid) {
            return true;
          }

          @Override
          public Boolean visitNoType(NoType t, Void aVoid) {
            return false;
          }

          @Override
          public Boolean visitArray(ArrayType t, Void aVoid) {
            return usesGenerics(t.getComponentType());
          }

          @Override
          public Boolean visitWildcard(WildcardType t, Void aVoid) {
            return true;
          }

          @Override
          public Boolean visitDeclared(DeclaredType t, Void aVoid) {
            try {
              return !t.getTypeArguments().isEmpty() || usesGenerics(t.getEnclosingType());
            } catch (CannotInferException e) {
              // InferredDeclaredTypes can only be obtained by calling asType on an
              // InferredTypeElement. Types that are used in signature generation are not obtained
              // in this manner, so they should always be StandaloneDeclaredTypes and this exception
              // should never happen.
              throw new AssertionError("Unexpected inferred type", e);
            }
          }
        },
        null);
  }
}
