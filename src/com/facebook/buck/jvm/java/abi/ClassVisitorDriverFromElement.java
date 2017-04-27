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
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementScanner8;
import javax.lang.model.util.Elements;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ClassVisitorDriverFromElement {
  private final DescriptorFactory descriptorFactory;
  private final SignatureFactory signatureFactory;
  private final SourceVersion targetVersion;
  private final Elements elements;

  /**
   * @param targetVersion the class file version to target, expressed as the corresponding Java
   *     source version
   */
  ClassVisitorDriverFromElement(SourceVersion targetVersion, Elements elements) {
    this.targetVersion = targetVersion;
    this.elements = elements;
    descriptorFactory = new DescriptorFactory(elements);
    signatureFactory = new SignatureFactory(descriptorFactory);
  }

  public void driveVisitor(TypeElement fullClass, ClassVisitor visitor) throws IOException {
    fullClass.accept(new ElementVisitorAdapter(), visitor);
    visitor.visitEnd();
  }

  /** Gets the class file version corresponding to the given source version constant. */
  private static int sourceVersionToClassFileVersion(SourceVersion version) {
    switch (version) {
      case RELEASE_0:
        return Opcodes.V1_1; // JVMS8 4.1: 1.0 and 1.1 both support version 45.3 (Opcodes.V1_1)
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

  private class ElementVisitorAdapter extends ElementScanner8<Void, ClassVisitor> {
    boolean classVisitorStarted = false;
    List<TypeElement> innerMembers = new ArrayList<>();

    // TODO(jkeljo): Type annotations

    @Override
    public Void visitType(TypeElement e, ClassVisitor visitor) {
      if (classVisitorStarted) {
        // Collect inner classes/interfaces and visit them at the end
        innerMembers.add(e);
        return null;
      }

      TypeMirror superclass = e.getSuperclass();
      if (superclass.getKind() == TypeKind.NONE) {
        superclass =
            Preconditions.checkNotNull(elements.getTypeElement("java.lang.Object")).asType();
      }
      // Static never makes it into the file for classes
      int accessFlags = AccessFlags.getAccessFlags(e) & ~Opcodes.ACC_STATIC;
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

      visitor.visit(
          sourceVersionToClassFileVersion(targetVersion),
          accessFlags,
          descriptorFactory.getInternalName(e),
          signatureFactory.getSignature(e),
          descriptorFactory.getInternalName(superclass),
          e.getInterfaces()
              .stream()
              .map(descriptorFactory::getInternalName)
              .toArray(size -> new String[size]));
      classVisitorStarted = true;

      if (e.getNestingKind() == NestingKind.MEMBER) {
        visitMemberClass(e, visitor);
      }

      visitAnnotations(e.getAnnotationMirrors(), visitor::visitAnnotation);

      super.visitType(e, visitor);

      // We visit all inner members in reverse to match the order in original, full jars
      for (TypeElement element : Lists.reverse(innerMembers)) {
        visitMemberClass(element, visitor);
      }

      return null;
    }

    private void visitMemberClass(TypeElement e, ClassVisitor visitor) {
      visitor.visitInnerClass(
          descriptorFactory.getInternalName(e),
          descriptorFactory.getInternalName((TypeElement) e.getEnclosingElement()),
          e.getSimpleName().toString(),
          AccessFlags.getAccessFlags(e) & ~Opcodes.ACC_SUPER);
      // We remove ACC_SUPER above because javac does as well for InnerClasses entries.
    }

    @Override
    public Void visitExecutable(ExecutableElement e, ClassVisitor visitor) {
      if (e.getModifiers().contains(Modifier.PRIVATE)) {
        return null;
      }

      // TODO(jkeljo): Bridge methods: Look at superclasses, then interfaces, checking whether
      // method types change in the new class

      String[] exceptions =
          e.getThrownTypes()
              .stream()
              .map(descriptorFactory::getInternalName)
              .toArray(count -> new String[count]);

      MethodVisitor methodVisitor =
          visitor.visitMethod(
              AccessFlags.getAccessFlags(e),
              e.getSimpleName().toString(),
              descriptorFactory.getDescriptor(e),
              signatureFactory.getSignature(e),
              exceptions);

      visitParameters(e.getParameters(), methodVisitor);
      visitDefaultValue(e, methodVisitor);
      visitAnnotations(e.getAnnotationMirrors(), methodVisitor::visitAnnotation);
      methodVisitor.visitEnd();

      return null;
    }

    private void visitParameters(
        List<? extends VariableElement> parameters, MethodVisitor methodVisitor) {
      for (int i = 0; i < parameters.size(); i++) {
        VariableElement parameter = parameters.get(i);
        for (AnnotationMirror annotationMirror : parameter.getAnnotationMirrors()) {
          if (MoreElements.isSourceRetention(annotationMirror)) {
            continue;
          }
          visitAnnotationValues(
              annotationMirror,
              methodVisitor.visitParameterAnnotation(
                  i,
                  descriptorFactory.getDescriptor(annotationMirror.getAnnotationType()),
                  MoreElements.isRuntimeRetention(annotationMirror)));
        }
      }
    }

    private void visitDefaultValue(ExecutableElement e, MethodVisitor methodVisitor) {
      AnnotationValue defaultValue = e.getDefaultValue();
      if (defaultValue == null) {
        return;
      }

      AnnotationVisitor annotationVisitor = methodVisitor.visitAnnotationDefault();
      visitAnnotationValue(null, defaultValue.getValue(), annotationVisitor);
      annotationVisitor.visitEnd();
    }

    @Override
    public Void visitVariable(VariableElement e, ClassVisitor classVisitor) {
      if (e.getModifiers().contains(Modifier.PRIVATE)) {
        return null;
      }

      FieldVisitor fieldVisitor =
          classVisitor.visitField(
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
        List<? extends AnnotationMirror> annotations, VisitorWithAnnotations visitor) {
      annotations.forEach(annotation -> visitAnnotation(annotation, visitor));
    }

    private void visitAnnotation(AnnotationMirror annotation, VisitorWithAnnotations visitor) {
      if (MoreElements.isSourceRetention(annotation)) {
        return;
      }
      AnnotationVisitor annotationVisitor =
          visitor.visitAnnotation(
              descriptorFactory.getDescriptor(annotation.getAnnotationType()),
              MoreElements.isRuntimeRetention(annotation));
      visitAnnotationValues(annotation, annotationVisitor);
      annotationVisitor.visitEnd();
    }

    private void visitAnnotationValues(
        AnnotationMirror annotation, AnnotationVisitor annotationVisitor) {
      visitAnnotationValues(annotation.getElementValues(), annotationVisitor);
    }

    private void visitAnnotationValues(
        Map<? extends ExecutableElement, ? extends AnnotationValue> elementValues,
        AnnotationVisitor visitor) {
      elementValues
          .entrySet()
          .forEach(
              entry ->
                  visitAnnotationValue(
                      entry.getKey().getSimpleName().toString(),
                      entry.getValue().getValue(),
                      visitor));
    }

    private void visitAnnotationValue(String name, Object value, AnnotationVisitor visitor) {

      if (value instanceof Boolean
          || value instanceof Byte
          || value instanceof Character
          || value instanceof Short
          || value instanceof Integer
          || value instanceof Long
          || value instanceof Float
          || value instanceof Double
          || value instanceof String) {
        visitAnnotationPrimitiveValue(name, value, visitor);
      } else if (value instanceof TypeMirror) {
        visitAnnotationTypeValue(name, (TypeMirror) value, visitor);
      } else if (value instanceof VariableElement) {
        visitAnnotationEnumValue(name, (VariableElement) value, visitor);
      } else if (value instanceof AnnotationMirror) {
        visitAnnotationAnnotationValue(name, (AnnotationMirror) value, visitor);
      } else if (value instanceof List) {
        @SuppressWarnings("unchecked") // See docs for AnnotationValue
        List<? extends AnnotationValue> listValue = (List<? extends AnnotationValue>) value;
        visitAnnotationArrayValue(name, listValue, visitor);
      } else {
        throw new IllegalArgumentException(
            String.format("Unexpected annotaiton value type: %s", value.getClass()));
      }
    }

    private void visitAnnotationPrimitiveValue(
        String name, Object value, AnnotationVisitor visitor) {
      visitor.visit(name, value);
    }

    private void visitAnnotationTypeValue(
        String name, TypeMirror value, AnnotationVisitor visitor) {
      visitor.visit(name, descriptorFactory.getType(value));
    }

    private void visitAnnotationEnumValue(
        String name, VariableElement value, AnnotationVisitor visitor) {
      visitor.visitEnum(
          name,
          descriptorFactory.getDescriptor(value.getEnclosingElement().asType()),
          value.getSimpleName().toString());
    }

    private void visitAnnotationAnnotationValue(
        String name, AnnotationMirror value, AnnotationVisitor visitor) {
      AnnotationVisitor annotationValueVisitor =
          visitor.visitAnnotation(name, descriptorFactory.getDescriptor(value.getAnnotationType()));
      visitAnnotationValues(value, annotationValueVisitor);
      annotationValueVisitor.visitEnd();
    }

    private void visitAnnotationArrayValue(
        String name, List<? extends AnnotationValue> value, AnnotationVisitor visitor) {
      AnnotationVisitor arrayMemberVisitor = visitor.visitArray(name);
      value.forEach(
          annotationValue ->
              visitAnnotationValue(null, annotationValue.getValue(), arrayMemberVisitor));
      arrayMemberVisitor.visitEnd();
    }
  }
}
