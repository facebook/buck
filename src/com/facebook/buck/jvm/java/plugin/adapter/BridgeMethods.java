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

package com.facebook.buck.jvm.java.plugin.adapter;

import com.facebook.buck.jvm.java.lang.model.BridgeMethod;
import com.facebook.buck.jvm.java.lang.model.ElementsExtended;
import com.facebook.buck.jvm.java.lang.model.MoreElements;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

public class BridgeMethods {
  private final Map<TypeElement, List<BridgeMethod>> allBridgeMethods = new HashMap<>();
  private final Map<TypeElement, Map<Name, List<BridgeMethod>>> bridgeMethodsByName =
      new HashMap<>();
  private final ElementsExtended elements;
  private final Types types;

  public BridgeMethods(ElementsExtended elements, Types types) {
    this.elements = elements;
    this.types = types;
  }

  public List<BridgeMethod> getBridgeMethods(TypeElement typeElement) {
    return allBridgeMethods.computeIfAbsent(typeElement, this::calculateBridgeMethods);
  }

  public List<BridgeMethod> getBridgeMethods(TypeElement typeElement, Name name) {
    return bridgeMethodsByName
        .computeIfAbsent(typeElement, this::calculateBridgeMethodsByName)
        .getOrDefault(name, Collections.emptyList());
  }

  public List<BridgeMethod> getBridgeMethodsNoCreate(TypeElement typeElement, Name name) {
    if (allBridgeMethods.containsKey(typeElement)) {
      return getBridgeMethods(typeElement, name);
    }

    return Collections.emptyList();
  }

  private Map<Name, List<BridgeMethod>> calculateBridgeMethodsByName(TypeElement inType) {
    Map<Name, List<BridgeMethod>> result = new HashMap<>();

    for (BridgeMethod bridgeMethod : getBridgeMethods(inType)) {
      result
          .computeIfAbsent(bridgeMethod.from.getSimpleName(), name -> new ArrayList<>())
          .add(bridgeMethod);
    }

    return result;
  }

  private List<BridgeMethod> calculateBridgeMethods(TypeElement inType) {
    List<TypeElement> supertypes =
        MoreElements.getTransitiveSuperclasses(inType).collect(Collectors.toList());

    for (int i = supertypes.size() - 1; i >= 0; i--) {
      TypeElement supertype = supertypes.get(i);
      if (!MoreElements.isTransitiveMemberClass(inType, supertype)) {
        allBridgeMethods.computeIfAbsent(
            supertype, it -> new BridgeMethodsFinder(it).findBridges());
      }
    }

    return new BridgeMethodsFinder(inType).findBridges();
  }

  private class BridgeMethodsFinder {
    private final TypeElement subclass;
    private final List<BridgeMethod> bridgesNeeded = new ArrayList<>();

    private BridgeMethodsFinder(TypeElement subclass) {
      this.subclass = subclass;
    }

    public List<BridgeMethod> findBridges() {
      MoreElements.getTransitiveSuperclasses(subclass).forEach(this::findBridges);
      MoreElements.getInterfaces(subclass).forEach(this::findBridges);
      return bridgesNeeded;
    }

    private void findBridges(TypeElement supertype) {
      findBridgesToType(supertype);
      MoreElements.getInterfaces(supertype).forEach(this::findBridges);
    }

    private void findBridgesToType(TypeElement supertype) {
      if (supertype == subclass) {
        return;
      }

      List<ExecutableElement> supertypeMethods =
          new ArrayList<>(ElementFilter.methodsIn(supertype.getEnclosedElements()));
      // For whatever reason, the members field in the compiler is reversed
      Collections.reverse(supertypeMethods);
      for (ExecutableElement supertypeMethod : supertypeMethods) {
        if (supertypeMethod.getModifiers().contains(Modifier.PRIVATE)
            || supertypeMethod.getModifiers().contains(Modifier.STATIC)
            || supertypeMethod.getModifiers().contains(Modifier.FINAL)
            || isHiddenInType(supertypeMethod, subclass)) {
          continue;
        }

        ExecutableElement languageResolvedMethod =
            elements.getImplementation(supertypeMethod, subclass);
        TypeElement vmResolvedMethodOwner =
            elements.getBinaryImplementationOwner(supertypeMethod, subclass);

        if (vmResolvedMethodOwner == null
            || vmResolvedMethodOwner == supertypeMethod.getEnclosingElement()
            || (languageResolvedMethod != null
                && !types.isSubtype(
                    types.erasure(vmResolvedMethodOwner.asType()),
                    types.erasure(languageResolvedMethod.getEnclosingElement().asType())))) {
          if (languageResolvedMethod != null
              && isBridgeNeeded(supertypeMethod, languageResolvedMethod, subclass)) {
            if (languageResolvedMethod.getEnclosingElement() != vmResolvedMethodOwner) {
              bridgesNeeded.add(new BridgeMethod(languageResolvedMethod, supertypeMethod));
            }
            // There's a comment in the compiler source about there being a design flaw in
            // Reflection
            // that requires bridge methods to be created if a public class inherits a public method
            // from a non-public supertype.
          } else if (languageResolvedMethod == supertypeMethod
              && languageResolvedMethod.getEnclosingElement() != subclass
              && !languageResolvedMethod.getModifiers().contains(Modifier.FINAL)
              && !supertypeMethod.getModifiers().contains(Modifier.ABSTRACT)
              && !supertypeMethod.getModifiers().contains(Modifier.DEFAULT)
              && supertypeMethod.getModifiers().contains(Modifier.PUBLIC)
              && subclass.getModifiers().contains(Modifier.PUBLIC)
              && !languageResolvedMethod
                  .getEnclosingElement()
                  .getModifiers()
                  .contains(Modifier.PUBLIC)) {
            bridgesNeeded.add(new BridgeMethod(supertypeMethod, supertypeMethod));
          }
        }
      }
    }

    private boolean isBridgeNeeded(
        ExecutableElement method, ExecutableElement languageImplementation, TypeElement inType) {
      if (method != languageImplementation) {
        ExecutableType methodErasure = (ExecutableType) types.erasure(method.asType());
        if (!isSameMemberWhenErased(inType, method, methodErasure)) {
          return true;
        }

        ExecutableType languageImplementationErasure =
            (ExecutableType) types.erasure(languageImplementation.asType());
        if (!isSameMemberWhenErased(
            inType, languageImplementation, languageImplementationErasure)) {
          return true;
        }

        return !types.isSameType(
            methodErasure.getReturnType(), languageImplementationErasure.getReturnType());
      } else if (!method.getModifiers().contains(Modifier.ABSTRACT)
          && !method.getModifiers().contains(Modifier.DEFAULT)) {
        return !isSameMemberWhenErased(inType, method, types.erasure(method.asType()));
      }

      return false;
    }

    private boolean isSameMemberWhenErased(
        TypeElement inType, ExecutableElement method, TypeMirror erasedType) {
      return types.isSameType(
          types.erasure(types.asMemberOf((DeclaredType) inType.asType(), method)), erasedType);
    }

    private boolean isHiddenInType(ExecutableElement hidden, TypeElement subclass) {
      for (TypeElement type = subclass; type != null; type = MoreElements.getSuperclass(type)) {
        for (ExecutableElement hider : elements.getDeclaredMethods(type, hidden.getSimpleName())) {
          if (elements.hides(hider, hidden)) {
            return true;
          }
        }
      }
      return false;
    }
  }
}
