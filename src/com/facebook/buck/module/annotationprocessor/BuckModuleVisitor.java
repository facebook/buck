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

package com.facebook.buck.module.annotationprocessor;

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
import javax.lang.model.util.SimpleElementVisitor6;
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
              "Cannot collect information Buck modules: " + e.getMessage(),
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
    String buckModuleName = getParamsFromAnnotationOrFail(type, buckModuleAnnotation, "id");
    List<String> dependentModules =
        getAnnotationParameterAsOptionalListOfStrings(buckModuleAnnotation, "dependencies");

    buckModuleDescriptors.add(
        new BuckModuleDescriptor(
            buckModuleAnnotationType, packageName, className, buckModuleName, dependentModules));
  }

  private String getParamsFromAnnotationOrFail(
      TypeElement type, AnnotationMirror annotation, String paramName) {

    String value = getAnnotationParameterAsString(annotation, paramName);

    if (value == null || value.isEmpty()) {
      processingEnv
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              "Required parameter '" + paramName + "' not found or is empty",
              type,
              annotation);
      throw new IllegalArgumentException();
    }

    return value;
  }

  private static List<String> getAnnotationParameterAsOptionalListOfStrings(
      AnnotationMirror annotation, String parameterName) {
    final List<?> parameters = (List<?>) getAnnotationParameter(annotation, parameterName);

    if (parameters == null) {
      return Collections.emptyList();
    }

    List<String> parametersAsStrings = new ArrayList<>();
    for (Object obj : parameters) {
      parametersAsStrings.add(obj.toString());
    }

    return parametersAsStrings;
  }

  @Nullable
  public static String getAnnotationParameterAsString(
      AnnotationMirror annotation, String parameterName) {
    return (String) getAnnotationParameter(annotation, parameterName);
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
