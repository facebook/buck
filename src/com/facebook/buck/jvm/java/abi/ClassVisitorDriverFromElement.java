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
import com.facebook.buck.jvm.java.lang.model.BridgeMethod;
import com.facebook.buck.jvm.java.lang.model.ElementsExtended;
import com.facebook.buck.jvm.java.lang.model.MoreElements;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.processing.Messager;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementScanner8;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ClassVisitorDriverFromElement {
  private final DescriptorFactory descriptorFactory;
  private final Messager messager;
  private final SignatureFactory signatureFactory;
  private final SourceVersion targetVersion;
  private final ElementsExtended elements;
  private final Types types;
  private final AccessFlags accessFlagsUtils;
  private final boolean includeParameterMetadata;

  /**
   * @param targetVersion the class file version to target, expressed as the corresponding Java
   *     source version
   * @param messager
   * @param types
   * @param includeParameterMetadata
   */
  ClassVisitorDriverFromElement(
      SourceVersion targetVersion,
      ElementsExtended elements,
      Messager messager,
      Types types,
      boolean includeParameterMetadata) {
    this.targetVersion = targetVersion;
    this.elements = elements;
    descriptorFactory = new DescriptorFactory(elements);
    this.messager = messager;
    this.types = types;
    this.includeParameterMetadata = includeParameterMetadata;
    signatureFactory = new SignatureFactory(descriptorFactory);
    accessFlagsUtils = new AccessFlags(elements);
  }

  public void driveVisitor(Element fullElement, ClassVisitor visitor) {
    fullElement.accept(new ElementVisitorAdapter(), visitor);
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

    @Override
    public Void visitPackage(PackageElement e, ClassVisitor classVisitor) {
      classVisitor.visit(
          sourceVersionToClassFileVersion(targetVersion),
          Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
          e.getQualifiedName().toString().replace('.', '/') + "/package-info",
          null,
          "java/lang/Object",
          new String[0]);

      visitAnnotations(e, classVisitor::visitAnnotation);

      new InnerClassesTable(descriptorFactory, accessFlagsUtils, e)
          .reportInnerClassReferences(classVisitor);

      classVisitor.visitEnd();

      return null;
    }

    // TODO(jkeljo): Type annotations

    @Override
    public Void visitType(TypeElement e, ClassVisitor visitor) {
      if (classVisitorStarted) {
        // We'll get inner class references later
        return null;
      }

      TypeMirror superclass = e.getSuperclass();
      if (superclass.getKind() == TypeKind.NONE) {
        superclass =
            Preconditions.checkNotNull(elements.getTypeElement("java.lang.Object")).asType();
      }

      int classFileVersion = sourceVersionToClassFileVersion(targetVersion);
      visitor.visit(
          classFileVersion,
          accessFlagsUtils.getAccessFlagsForClassNode(e),
          descriptorFactory.getInternalName(e),
          signatureFactory.getSignature(e),
          descriptorFactory.getInternalName(superclass),
          e.getInterfaces()
              .stream()
              .map(descriptorFactory::getInternalName)
              .toArray(size -> new String[size]));
      classVisitorStarted = true;

      visitAnnotations(e, visitor::visitAnnotation);

      super.visitType(e, visitor);

      InnerClassesTable innerClassesTable =
          new InnerClassesTable(descriptorFactory, accessFlagsUtils, e);
      if (e.getKind().isClass() || classFileVersion >= Opcodes.V1_8) {
        try {
          generateBridges(e, visitor, innerClassesTable);
        } catch (UnsupportedOperationException | CannotInferException ex) { // NOPMD
          // Can't generate bridges in source-only mode
        }
      }

      innerClassesTable.reportInnerClassReferences(visitor);

      return null;
    }

    private void generateBridges(
        TypeElement subclass, ClassVisitor visitor, InnerClassesTable innerClassesTable) {
      new BridgeBuilder(subclass, visitor, innerClassesTable).generateBridges();
    }

    /** Creates bridge methods in a given subclass as needed. */
    private class BridgeBuilder {
      private final TypeElement subclass;
      private final ClassVisitor visitor;
      private final InnerClassesTable innerClassesTable;
      private final Multimap<Name, String> bridgedDescriptors = HashMultimap.create();

      private BridgeBuilder(
          TypeElement subclass, ClassVisitor visitor, InnerClassesTable innerClassesTable) {
        this.subclass = subclass;
        this.visitor = visitor;
        this.innerClassesTable = innerClassesTable;
      }

      public void generateBridges() {
        for (BridgeMethod bridgeMethod : elements.getAllBridgeMethods(subclass)) {
          generateBridge(bridgeMethod.from, bridgeMethod.to);
        }
      }

      private void generateBridge(ExecutableElement fromMethod, ExecutableElement toMethod) {
        ExecutableType toErasure = (ExecutableType) types.erasure(toMethod.asType());
        String bridgeDescriptor = descriptorFactory.getDescriptor(toErasure);
        if (bridgedDescriptors.get(toMethod.getSimpleName()).contains(bridgeDescriptor)) {
          return;
        }

        innerClassesTable.addTypeReferences(toErasure);

        String[] exceptions =
            toErasure
                .getThrownTypes()
                .stream()
                .map(descriptorFactory::getInternalName)
                .toArray(String[]::new);

        MethodVisitor methodVisitor =
            visitor.visitMethod(
                getBridgeAccessFlags(fromMethod),
                toMethod.getSimpleName().toString(),
                bridgeDescriptor,
                signatureFactory.getSignature(toErasure),
                exceptions);
        if (methodVisitor != null) {
          List<? extends VariableElement> fromMethodParameters = fromMethod.getParameters();
          for (int i = 0; i < fromMethodParameters.size(); i++) {
            VariableElement fromMethodParameter = fromMethodParameters.get(i);
            List<? extends AnnotationMirror> annotations =
                fromMethodParameter.getAnnotationMirrors();
            innerClassesTable.addTypeReferences(annotations);
            visitParameter(
                i,
                fromMethodParameter,
                fromMethodParameter.getSimpleName(),
                accessFlagsUtils.getAccessFlags(fromMethodParameter) | Opcodes.ACC_SYNTHETIC,
                annotations,
                false,
                methodVisitor);
          }
          innerClassesTable.addTypeReferences(fromMethod.getAnnotationMirrors());
          visitAnnotations(fromMethod, methodVisitor::visitAnnotation);
          methodVisitor.visitEnd();

          bridgedDescriptors.put(toMethod.getSimpleName(), bridgeDescriptor);
        }
      }

      private int getBridgeAccessFlags(ExecutableElement method) {
        return accessFlagsUtils.getAccessFlags(method)
                & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)
            | Opcodes.ACC_SYNTHETIC
            | Opcodes.ACC_BRIDGE;
      }
    }

    @Override
    public Void visitExecutable(ExecutableElement e, ClassVisitor visitor) {
      if (e.getModifiers().contains(Modifier.PRIVATE)) {
        return null;
      }

      String[] exceptions =
          e.getThrownTypes()
              .stream()
              .map(descriptorFactory::getInternalName)
              .toArray(count -> new String[count]);

      MethodVisitor methodVisitor =
          visitor.visitMethod(
              accessFlagsUtils.getAccessFlags(e),
              e.getSimpleName().toString(),
              descriptorFactory.getDescriptor(e),
              signatureFactory.getSignature(e),
              exceptions);

      visitParameters(e.getParameters(), methodVisitor, MoreElements.isInnerClassConstructor(e));
      visitDefaultValue(e, methodVisitor);
      visitAnnotations(e, methodVisitor::visitAnnotation);
      methodVisitor.visitEnd();

      return null;
    }

    private void visitParameters(
        List<? extends VariableElement> parameters,
        MethodVisitor methodVisitor,
        boolean isInnerClassConstructor) {
      if (isInnerClassConstructor) {
        // ASM uses a fake annotation to indicate synthetic parameters
        methodVisitor.visitParameterAnnotation(0, "Ljava/lang/Synthetic;", false);
      }
      for (int i = 0; i < parameters.size(); i++) {
        VariableElement parameter = parameters.get(i);
        visitParameter(
            i,
            parameter,
            parameter.getSimpleName(),
            accessFlagsUtils.getAccessFlags(parameter),
            parameter.getAnnotationMirrors(),
            isInnerClassConstructor,
            methodVisitor);
      }
    }

    private void visitParameter(
        int index,
        VariableElement parameter,
        Name name,
        int access,
        List<? extends AnnotationMirror> annotationMirrors,
        boolean isInnerClassConstructor,
        MethodVisitor methodVisitor) {
      if (includeParameterMetadata) {
        methodVisitor.visitParameter(name.toString(), access);
      }

      for (AnnotationMirror annotationMirror : annotationMirrors) {
        try {
          if (MoreElements.isSourceRetention(annotationMirror)) {
            continue;
          }
        } catch (CannotInferException e) {
          reportMissingAnnotationType(parameter, annotationMirror);
          continue;
        }
        visitAnnotationValues(
            annotationMirror,
            methodVisitor.visitParameterAnnotation(
                isInnerClassConstructor ? index + 1 : index,
                descriptorFactory.getDescriptor(annotationMirror.getAnnotationType()),
                MoreElements.isRuntimeRetention(annotationMirror)));
      }
    }

    private void reportMissingAnnotationType(Element element, AnnotationMirror annotationMirror) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      messager.printMessage(
          Kind.ERROR,
          String.format(
              "Could not find the annotation %1$s.\n"
                  + "This can happen for one of two reasons:\n"
                  + "1. A dependency is missing in the BUCK file for the current target. "
                  + "Try building the current rule without the #source-only-abi flavor, "
                  + "fix any errors that are reported, and then build this flavor again.\n"
                  + "2. The rule that owns %1$s is not marked with "
                  + "required_for_source_only_abi = True. Add that parameter to the rule and try "
                  + "again.",
              annotationType),
          element,
          annotationMirror);
    }

    private void visitDefaultValue(ExecutableElement e, MethodVisitor methodVisitor) {
      AnnotationValue defaultValue = e.getDefaultValue();
      if (defaultValue == null) {
        return;
      }

      AnnotationVisitor annotationVisitor = methodVisitor.visitAnnotationDefault();
      visitAnnotationValue(null, defaultValue, annotationVisitor);
      annotationVisitor.visitEnd();
    }

    @Override
    public Void visitVariable(VariableElement e, ClassVisitor classVisitor) {
      if (e.getModifiers().contains(Modifier.PRIVATE)) {
        return null;
      }

      FieldVisitor fieldVisitor =
          classVisitor.visitField(
              accessFlagsUtils.getAccessFlags(e),
              e.getSimpleName().toString(),
              descriptorFactory.getDescriptor(e),
              signatureFactory.getSignature(e),
              e.getConstantValue());
      visitAnnotations(e, fieldVisitor::visitAnnotation);
      fieldVisitor.visitEnd();

      return null;
    }

    private void visitAnnotations(Element enclosingElement, VisitorWithAnnotations visitor) {
      enclosingElement
          .getAnnotationMirrors()
          .forEach(annotation -> visitAnnotation(enclosingElement, annotation, visitor));
    }

    private void visitAnnotation(
        Element enclosingElement, AnnotationMirror annotation, VisitorWithAnnotations visitor) {
      try {
        if (MoreElements.isSourceRetention(annotation)) {
          return;
        }
        AnnotationVisitor annotationVisitor =
            visitor.visitAnnotation(
                descriptorFactory.getDescriptor(annotation.getAnnotationType()),
                MoreElements.isRuntimeRetention(annotation));
        visitAnnotationValues(annotation, annotationVisitor);
        annotationVisitor.visitEnd();
      } catch (CannotInferException e) {
        reportMissingAnnotationType(enclosingElement, annotation);
      }
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
                      entry.getKey().getSimpleName().toString(), entry.getValue(), visitor));
    }

    private void visitAnnotationValue(
        @Nullable String name, AnnotationValue value, AnnotationVisitor visitor) {
      value.accept(new AnnotationVisitorAdapter(name, visitor), null);
    }

    private class AnnotationVisitorAdapter extends SimpleAnnotationValueVisitor8<Void, Void> {
      @Nullable private final String name;
      private final AnnotationVisitor visitor;

      private AnnotationVisitorAdapter(@Nullable String name, AnnotationVisitor visitor) {
        this.name = name;
        this.visitor = visitor;
      }

      @Override
      protected Void defaultAction(Object value, Void aVoid) {
        visitor.visit(name, value);
        return null;
      }

      @Override
      public Void visitType(TypeMirror value, Void aVoid) {
        visitor.visit(name, descriptorFactory.getType(value));
        return null;
      }

      @Override
      public Void visitEnumConstant(VariableElement value, Void aVoid) {
        visitor.visitEnum(
            name,
            descriptorFactory.getDescriptor(value.getEnclosingElement().asType()),
            value.getSimpleName().toString());
        return null;
      }

      @Override
      public Void visitAnnotation(AnnotationMirror value, Void aVoid) {
        AnnotationVisitor annotationValueVisitor =
            visitor.visitAnnotation(
                name, descriptorFactory.getDescriptor(value.getAnnotationType()));
        visitAnnotationValues(value, annotationValueVisitor);
        annotationValueVisitor.visitEnd();
        return null;
      }

      @Override
      public Void visitArray(List<? extends AnnotationValue> listValue, Void aVoid) {
        AnnotationVisitor arrayMemberVisitor = visitor.visitArray(name);
        listValue.forEach(
            annotationValue -> visitAnnotationValue(null, annotationValue, arrayMemberVisitor));
        arrayMemberVisitor.visitEnd();
        return null;
      }
    }
  }
}
