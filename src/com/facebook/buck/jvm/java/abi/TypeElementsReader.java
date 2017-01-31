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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * A {@link LibraryReader} that reads from a list of {@link TypeElement}s and their
 * inner types.
 */
class TypeElementsReader implements LibraryReader<TypeElement> {
  private final Elements elements;
  private final Iterable<TypeElement> topLevelTypes;
  @Nullable
  private Map<Path, TypeElement> allTypes;

  TypeElementsReader(Elements elements, Iterable<TypeElement> topLevelTypes) {
    this.elements = elements;
    this.topLevelTypes = topLevelTypes;
  }

  @Override
  public List<Path> getRelativePaths() throws IOException {
    return new ArrayList<>(getAllTypes().keySet());
  }

  @Override
  public InputStream openResourceFile(Path relativePath) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypeElement openClass(Path relativePath) throws IOException {
    return Preconditions.checkNotNull(getAllTypes().get(relativePath));
  }

  @Override
  public void close() throws IOException {
    // Nothing
  }

  private Map<Path, TypeElement> getAllTypes() {
    if (allTypes == null) {
      allTypes = new LinkedHashMap<>();
      topLevelTypes.forEach(type -> addTypeElements(type, allTypes));
    }

    return allTypes;
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
    return Paths.get(String.format(
        "%s.class",
        elements.getBinaryName(typeElement).toString().replace('.', File.separatorChar)));
  }
}
