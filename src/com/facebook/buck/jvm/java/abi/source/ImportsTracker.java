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

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.sun.source.util.TreePath;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Simulates import resolution scopes in a source-only ABI classpath. */
class ImportsTracker {
  private static class StaticImport {
    public final TypeElement container;
    public final Name identifier;

    private StaticImport(TypeElement container, Name identifier) {
      this.container = container;
      this.identifier = identifier;
    }
  }

  private final Map<TypeElement, TreePath> importedTypes = new HashMap<>();
  private final Map<QualifiedNameable, TreePath> importedOwners = new HashMap<>();
  private final Set<TypeElement> staticImportedOwners = new HashSet<>();
  private final Map<Name, StaticImport> staticImports = new HashMap<>();
  private final Types types;

  public ImportsTracker(Elements elements, Types types, PackageElement enclosingPackage) {
    this.types = types;
    importMembers(Objects.requireNonNull(elements.getPackageElement("java.lang")), null);
    importMembers(enclosingPackage, null);
  }

  public void importType(TypeElement type, TreePath location) {
    importedTypes.put(type, location);
  }

  public void importMembers(QualifiedNameable typeOrPackage, @Nullable TreePath location) {
    importedOwners.put(typeOrPackage, location);
  }

  public void importStatic(TypeElement container, Name identifier) {
    StaticImport newImport = new StaticImport(container, identifier);
    staticImports.put(newImport.identifier, newImport);
  }

  public void importStaticMembers(TypeElement container) {
    staticImportedOwners.add(container);
  }

  public boolean isStaticImported(TypeElement element) {
    return getStaticImportContainer(element) != null;
  }

  public boolean nameIsStaticImported(Name name) {
    return staticImports.containsKey(name);
  }

  @Nullable
  public TypeElement getStaticImportContainer(TypeElement element) {
    if (!element.getModifiers().contains(Modifier.STATIC)) {
      return null;
    }

    StaticImport candidate = staticImports.get(element.getSimpleName());

    if (candidate != null
        && types.isSubtype(candidate.container.asType(), element.getEnclosingElement().asType())) {
      return candidate.container;
    }

    return null;
  }

  public boolean isSingleTypeImported(Element element) {
    return getSingleTypeImportLocation(element) != null;
  }

  @Nullable
  public TreePath getSingleTypeImportLocation(Element element) {
    return importedTypes.get(element);
  }

  public boolean isOnDemandImported(Element element) {
    return getOnDemandImportLocation(element) != null;
  }

  @Nullable
  public TreePath getOnDemandImportLocation(Element element) {
    return importedOwners.get(element.getEnclosingElement());
  }

  public boolean isOnDemandStaticImported(TypeElement element) {
    return getOnDemandStaticImportContainer(element) != null;
  }

  @Nullable
  public TypeElement getOnDemandStaticImportContainer(TypeElement element) {
    if (!element.getModifiers().contains(Modifier.STATIC)) {
      return null;
    }

    TypeElement container = (TypeElement) element.getEnclosingElement();
    for (TypeElement staticImportedOwner : staticImportedOwners) {
      if (types.isSubtype(staticImportedOwner.asType(), container.asType())) {
        return staticImportedOwner;
      }
    }

    return null;
  }
}
