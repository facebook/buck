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

import com.facebook.buck.jvm.java.lang.model.ElementsExtended;
import com.facebook.buck.jvm.java.lang.model.MoreElements;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

/**
 * Wraps and extends {@link javax.lang.model.util.Elements} with methods that cannot be added as
 * pure extension methods on {@link MoreElements} because they require per-instance state.
 */
public class ElementsExtendedImpl extends DelegatingElements implements ElementsExtended {
  private final Map<TypeElement, Map<Name, List<ExecutableElement>>> methodsMaps = new HashMap<>();
  private final Trees trees;

  public ElementsExtendedImpl(Elements inner, Trees trees) {
    super(inner);
    this.trees = trees;
  }

  @Override
  public List<ExecutableElement> getDeclaredMethods(TypeElement owner, CharSequence name) {
    Map<Name, List<ExecutableElement>> methodsMap =
        methodsMaps.computeIfAbsent(owner, ElementsExtendedImpl::buildMethodsMap);

    List<ExecutableElement> result = methodsMap.get(getName(name));
    if (result == null) {
      result = Collections.emptyList();
    }
    return result;
  }

  @Override
  public boolean isCompiledInCurrentRun(Element element) {
    return trees.getTree(element) != null;
  }

  private static Map<Name, List<ExecutableElement>> buildMethodsMap(TypeElement owner) {
    Map<Name, List<ExecutableElement>> result = new HashMap<>();

    for (ExecutableElement method : ElementFilter.methodsIn(owner.getEnclosedElements())) {
      List<ExecutableElement> methodsWithName =
          result.computeIfAbsent(method.getSimpleName(), ignored -> new ArrayList<>());
      methodsWithName.add(method);
    }

    return result;
  }
}
