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

import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * An annotation processor that generates a stub jar for all types discovered during compilation.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
class StubJarGeneratingProcessor extends AbstractProcessor {
  private final ProjectFilesystem filesystem;
  private final Path stubJarPath;
  private final Set<Name> topLevelTypeNames = new LinkedHashSet<>();
  private final SourceVersion classFileVersion;

  /**
   * @param classFileVersion the class file version to output, expressed as the corresponding Java
   *                         source version
   */
  public StubJarGeneratingProcessor(
      ProjectFilesystem filesystem,
      Path stubJarPath,
      SourceVersion classFileVersion) {
    this.filesystem = filesystem;
    this.stubJarPath = stubJarPath;
    this.classFileVersion = classFileVersion;
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (!roundEnv.processingOver()) {
      roundEnv.getRootElements().stream()
          .filter(element -> element.getKind() != ElementKind.PACKAGE)
          .map(element -> ((QualifiedNameable) element).getQualifiedName())
          .forEachOrdered(topLevelTypeNames::add);
    } else {
      generateStubJar();
    }

    return false;
  }

  private void generateStubJar() {
    try {
      Elements elementUtils = processingEnv.getElementUtils();
      StubJar stubJar = new StubJar(
          classFileVersion,
          elementUtils,
          topLevelTypeNames.stream()
              .map(elementUtils::getTypeElement)
              .collect(Collectors.toList()));
      stubJar.writeTo(filesystem, stubJarPath);
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Failed to create stub jar: %s. %s", stubJarPath, e.getMessage()), e);
    }
  }

  public Path getStubJarPath() {
    return Preconditions.checkNotNull(stubJarPath);
  }
}
