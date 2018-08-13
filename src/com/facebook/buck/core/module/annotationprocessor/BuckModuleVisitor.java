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

package com.facebook.buck.core.module.annotationprocessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleElementVisitor6;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Collects information from {@code BuckModule} annotations to a list of Buck module descriptors.
 */
class BuckModuleVisitor extends SimpleElementVisitor6<Void, TypeElement> {

  private final ProcessingEnvironment processingEnv;

  private final List<BuckModuleDescriptor> buckModuleDescriptors = new ArrayList<>();

  private boolean hadError = false;

  BuckModuleVisitor(ProcessingEnvironment processingEnv) {
    this.processingEnv = processingEnv;
  }

  public boolean hasData() {
    return !hadError && !buckModuleDescriptors.isEmpty();
  }

  List<BuckModuleDescriptor> getBuckModuleDescriptors() {
    return buckModuleDescriptors;
  }

  @Override
  public Void visitType(TypeElement type, TypeElement annotation) {
    try {
      visitTypeInternal(type, annotation);
    } catch (Exception e) {
      processingEnv
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              "Cannot collect information about Buck modules: " + ThrowablesUtils.toString(e),
              type);
      hadError = true;
    }

    return null;
  }

  private void visitTypeInternal(TypeElement type, TypeElement annotation) {
    String buckModuleAnnotationClass = annotation.getQualifiedName().toString();

    AnnotationMirror buckModuleAnnotation = getAnnotation(type, buckModuleAnnotationClass);

    if (buckModuleAnnotation == null) {
      return;
    }

    processBuckModuleAnnotation(type, annotation, buckModuleAnnotation);
  }

  @Nullable
  public static AnnotationMirror getAnnotation(Element element, String annotationTypeName) {
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      if (annotationTypeName.equals(mirror.getAnnotationType().toString())) {
        return mirror;
      }
    }

    return null;
  }

  private void processBuckModuleAnnotation(
      TypeElement type,
      TypeElement buckModuleAnnotationType,
      AnnotationMirror buckModuleAnnotation) {
    String packageName = getPackageName(type);
    String className = type.getSimpleName().toString();
    List<String> dependencies = extractDependencies(buckModuleAnnotation);

    buckModuleDescriptors.add(
        new BuckModuleDescriptor(
            buckModuleAnnotationType, packageName, className, packageName, dependencies));
  }

  private List<String> extractDependencies(AnnotationMirror buckModuleAnnotation) {
    List<TypeElement> dependenciesTypes =
        getAnnotationParameterAsOptionalListOfTypeElements(buckModuleAnnotation, "dependencies");

    List<String> dependenciesIds = new ArrayList<>();

    for (TypeElement dependencyType : dependenciesTypes) {
      AnnotationMirror dependencyBuckModuleAnnotation =
          getAnnotation(
              dependencyType, BuckModuleAnnotationProcessorConstants.BUCK_MODULE_ANNOTATION);

      if (dependencyBuckModuleAnnotation == null) {
        throw new RuntimeException("Could not find BuckModule annotation in " + dependencyType);
      }

      String dependencyBuckModuleId = getPackageName(dependencyType);

      dependenciesIds.add(dependencyBuckModuleId);
    }

    return dependenciesIds;
  }

  private List<TypeElement> getAnnotationParameterAsOptionalListOfTypeElements(
      AnnotationMirror annotation, String parameterName) {
    Object parameter = getAnnotationParameter(annotation, parameterName);

    if (parameter == null) {
      return Collections.emptyList();
    }

    if (!(parameter instanceof List)) {
      throw new RuntimeException("Invalid type of " + parameter + ". Need to be a List");
    }

    @SuppressWarnings("unchecked")
    List<AnnotationValue> parameters = (List<AnnotationValue>) parameter;

    Types types = processingEnv.getTypeUtils();
    List<TypeElement> typeElements = new ArrayList<>();
    for (AnnotationValue annotationValue : parameters) {
      typeElements.add(convertAnnotationValueToTypeElement(types, annotationValue));
    }

    return typeElements;
  }

  private static TypeElement convertAnnotationValueToTypeElement(
      Types types, AnnotationValue annotationValue) {
    if (!(annotationValue.getValue() instanceof TypeMirror)) {
      throw new RuntimeException(
          "Invalid type of " + annotationValue + ". Need to be a TypeMirror");
    }
    Element element = types.asElement((TypeMirror) annotationValue.getValue());
    if (!(element instanceof TypeElement)) {
      throw new RuntimeException("Invalid type of " + element + ". Need to be a TypeElement");
    }
    return (TypeElement) element;
  }

  @Nullable
  public static Object getAnnotationParameter(AnnotationMirror annotation, String parameterName) {
    if (annotation == null) {
      return null;
    }
    return getAnnotationParameter(parameterName, annotation.getElementValues());
  }

  @Nullable
  private static Object getAnnotationParameter(
      String parameterName, Map<? extends ExecutableElement, ? extends AnnotationValue> values) {
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        values.entrySet()) {
      if (parameterName.equals(entry.getKey().getSimpleName().toString())) {
        return entry.getValue().getValue();
      }
    }
    return null;
  }

  public String getPackageName(Element element) {
    return processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
  }
}
