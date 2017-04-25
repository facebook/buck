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
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import org.objectweb.asm.ClassVisitor;

/** A {@link LibraryReader} that reads from a list of {@link TypeElement}s and their inner types. */
class TypeElementsReader implements LibraryReader {
  private final SourceVersion targetVersion;
  private final Elements elements;
  private final Supplier<Map<Path, TypeElement>> allTypes;

  TypeElementsReader(
      SourceVersion targetVersion, Elements elements, Iterable<TypeElement> topLevelTypes) {
    this.targetVersion = targetVersion;
    this.elements = elements;
    this.allTypes =
        Suppliers.memoize(
            () -> {
              Map<Path, TypeElement> types = new LinkedHashMap<>();
              topLevelTypes.forEach(type -> addTypeElements(type, types));
              return types;
            });
  }

  @Override
  public List<Path> getRelativePaths() throws IOException {
    return new ArrayList<>(allTypes.get().keySet());
  }

  @Override
  public InputStream openResourceFile(Path relativePath) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void visitClass(Path relativePath, ClassVisitor cv) throws IOException {
    TypeElement typeElement = Preconditions.checkNotNull(allTypes.get().get(relativePath));
    new ClassVisitorDriverFromElement(targetVersion, elements).driveVisitor(typeElement, cv);
  }

  @Override
  public void close() throws IOException {
    // Nothing
  }

  private void addTypeElements(Element rootElement, Map<Path, TypeElement> typeElements) {
    if (!rootElement.getKind().isClass() && !rootElement.getKind().isInterface()) {
      return;
    }

    TypeElement typeElement = (TypeElement) rootElement;
    typeElements.put(getRelativePath(typeElement), typeElement);
    for (Element enclosed : typeElement.getEnclosedElements()) {
      addTypeElements(enclosed, typeElements);
    }
  }

  private Path getRelativePath(TypeElement typeElement) {
    return Paths.get(
        String.format(
            "%s.class",
            elements.getBinaryName(typeElement).toString().replace('.', File.separatorChar)));
  }
}
