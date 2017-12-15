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

import com.facebook.buck.jvm.java.lang.model.ElementsExtended;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.processing.Messager;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Types;
import org.objectweb.asm.ClassVisitor;

/** A {@link LibraryReader} that reads from a list of {@link Element}s and their inner types. */
class ElementsReader implements LibraryReader {
  private final SourceVersion targetVersion;
  private final ElementsExtended elements;
  private final Types types;
  private final Messager messager;
  private final Supplier<Map<Path, Element>> allElements;
  private final boolean includeParameterMetadata;

  ElementsReader(
      SourceVersion targetVersion,
      ElementsExtended elements,
      Types types,
      Messager messager,
      Iterable<Element> topLevelElements,
      boolean includeParameterMetadata) {
    this.targetVersion = targetVersion;
    this.elements = elements;
    this.types = types;
    this.messager = messager;
    this.allElements =
        MoreSuppliers.memoize(
            () -> {
              Map<Path, Element> allElements = new LinkedHashMap<>();
              topLevelElements.forEach(element -> addAllElements(element, allElements));
              return allElements;
            });
    this.includeParameterMetadata = includeParameterMetadata;
  }

  @Override
  public List<Path> getRelativePaths() throws IOException {
    return new ArrayList<>(allElements.get().keySet());
  }

  @Override
  public InputStream openResourceFile(Path relativePath) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void visitClass(Path relativePath, ClassVisitor cv) throws IOException {
    Element element = Preconditions.checkNotNull(allElements.get().get(relativePath));
    new ClassVisitorDriverFromElement(
            targetVersion, elements, messager, types, includeParameterMetadata)
        .driveVisitor(element, cv);
  }

  @Override
  public void close() throws IOException {
    // Nothing
  }

  private void addAllElements(Element rootElement, Map<Path, Element> elements) {
    if (rootElement.getKind() == ElementKind.PACKAGE) {
      PackageElement packageElement = (PackageElement) rootElement;
      if (!packageElement.getAnnotationMirrors().isEmpty()) {
        elements.put(
            getRelativePathToClass(packageElement.getQualifiedName().toString() + ".package-info"),
            packageElement);
      }
    }

    if (!rootElement.getKind().isClass() && !rootElement.getKind().isInterface()) {
      return;
    }

    TypeElement typeElement = (TypeElement) rootElement;
    elements.put(getRelativePath(typeElement), typeElement);
    for (Element enclosed : typeElement.getEnclosedElements()) {
      addAllElements(enclosed, elements);
    }
  }

  private Path getRelativePathToClass(CharSequence classBinaryName) {
    return Paths.get(
        String.format("%s.class", classBinaryName.toString().replace('.', File.separatorChar)));
  }

  private Path getRelativePath(TypeElement typeElement) {
    return getRelativePathToClass(elements.getBinaryName(typeElement));
  }
}
