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

import com.google.common.base.Preconditions;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementScanner8;
import javax.lang.model.util.Elements;

class ClassVisitorDriverFromElement {
  private final DescriptorFactory descriptorFactory;
  private final SignatureFactory signatureFactory;
  private final SourceVersion targetVersion;

  private final ElementVisitor<Void, ClassVisitor> elementVisitorAdapter =
      new ElementScanner8<Void, ClassVisitor>() {
        //boolean classVisitorStarted = false;

        @Override
        public Void visitType(TypeElement e, ClassVisitor visitor) {
          // TODO(jkeljo): Skip anonymous and local
          // TODO(jkeljo): inner class

          visitor.visit(
              sourceVersionToClassFileVersion(targetVersion),
              AccessFlags.getAccessFlags(e),
              descriptorFactory.getInternalName(e),
              signatureFactory.getSignature(e),
              descriptorFactory.getInternalName(e.getSuperclass()),
              e.getInterfaces().stream()
                  .map(descriptorFactory::getInternalName)
                  .toArray(size -> new String[size]));

          // TODO(jkeljo): outer class
          visitAnnotations(e.getAnnotationMirrors(), visitor::visitAnnotation);
          super.visitType(e, visitor);

          return null;
        }

        @Override
        public Void visitExecutable(ExecutableElement e, ClassVisitor visitor) {
          if (e.getModifiers().contains(Modifier.PRIVATE)) {
            return null;
          }

          String[] exceptions = null;  // TODO(jkeljo): Handle throws

          MethodVisitor methodVisitor = visitor.visitMethod(
              AccessFlags.getAccessFlags(e),
              e.getSimpleName().toString(),
              descriptorFactory.getDescriptor(e),
              signatureFactory.getSignature(e),
              exceptions);

          // TODO(jkeljo): parameters
          // TODO(jkeljo): type parameters

          visitAnnotations(e.getAnnotationMirrors(), methodVisitor::visitAnnotation);
          methodVisitor.visitEnd();

          return null;
        }

        @Override
        public Void visitVariable(VariableElement e, ClassVisitor classVisitor) {
          if (e.getModifiers().contains(Modifier.PRIVATE)) {
            return null;
          }

          FieldVisitor fieldVisitor = classVisitor.visitField(
              AccessFlags.getAccessFlags(e),
              e.getSimpleName().toString(),
              descriptorFactory.getDescriptor(e),
              signatureFactory.getSignature(e),
              e.getConstantValue());
          visitAnnotations(e.getAnnotationMirrors(), fieldVisitor::visitAnnotation);

          fieldVisitor.visitEnd();

          return null;
        }

        private void visitAnnotations(
            List<? extends AnnotationMirror> annotations,
            VisitorWithAnnotations visitor) {
          annotations.forEach(annotation -> visitAnnotation(annotation, visitor));
        }

        private void visitAnnotation(AnnotationMirror annotation, VisitorWithAnnotations visitor) {
          AnnotationVisitor annotationVisitor = visitor.visitAnnotation(
              descriptorFactory.getDescriptor(annotation.getAnnotationType()),
              isRuntimeVisible(annotation));

          // TODO(jkeljo): Values

          annotationVisitor.visitEnd();
        }
      };

  /**
   * @param targetVersion the class file version to target, expressed as the corresponding Java
   *                      source version
   */
  ClassVisitorDriverFromElement(SourceVersion targetVersion, Elements elements) {
    this.targetVersion = targetVersion;
    descriptorFactory = new DescriptorFactory(elements);
    signatureFactory = new SignatureFactory(descriptorFactory);
  }

  public void driveVisitor(TypeElement fullClass, ClassVisitor visitor) throws IOException {
    fullClass.accept(elementVisitorAdapter, visitor);
    visitor.visitEnd();
  }

  private static boolean isRuntimeVisible(AnnotationMirror annotation) {
    DeclaredType annotationType = annotation.getAnnotationType();
    TypeElement annotationTypeElement = (TypeElement) annotationType.asElement();

    AnnotationMirror retentionAnnotation = findAnnotation(
        "java.lang.annotation.Retention",
        annotationTypeElement);
    if (retentionAnnotation == null) {
      return false;
    }

    VariableElement retentionPolicy = (VariableElement) Preconditions.checkNotNull(
        findAnnotationValue(
          retentionAnnotation,
          "value"));

    return retentionPolicy.getSimpleName().contentEquals("RUNTIME");
  }

  @Nullable
  private static AnnotationMirror findAnnotation(CharSequence name, Element element) {
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      TypeElement annotationTypeElement = (TypeElement) annotationType.asElement();

      if (annotationTypeElement.getQualifiedName().contentEquals(name)) {
        return annotationMirror;
      }
    }

    return null;
  }

  @Nullable
  private static Object findAnnotationValue(AnnotationMirror annotation, String name) {
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        annotation.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(name)) {
        return entry.getValue().getValue();
      }
    }

    return null;
  }

  /**
   * Gets the class file version corresponding to the given source version constant.
   */
  private static int sourceVersionToClassFileVersion(SourceVersion version) {
    switch (version) {
      case RELEASE_0:
        return Opcodes.V1_1;  // JVMS8 4.1: 1.0 and 1.1 both support version 45.3 (Opcodes.V1_1)
      case RELEASE_1:
        return Opcodes.V1_1;
      case RELEASE_2:
        return Opcodes.V1_2;
      case RELEASE_3:
        return Opcodes.V1_3;
      case RELEASE_4:
        return Opcodes.V1_4;
      case RELEASE_5:
        return Opcodes.V1_5;
      case RELEASE_6:
        return Opcodes.V1_6;
      case RELEASE_7:
        return Opcodes.V1_7;
      case RELEASE_8:
        return Opcodes.V1_8;
      default:
        throw new IllegalArgumentException(String.format("Unexpected source version: %s", version));
    }
  }

  private interface VisitorWithAnnotations {
    AnnotationVisitor visitAnnotation(String desc, boolean visible);
  }
}
