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
import com.sun.source.tree.Tree;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.Parameterizable;
import javax.lang.model.element.TypeParameterElement;

abstract class TreeBackedParameterizable extends TreeBackedElement implements Parameterizable {
  private final List<TreeBackedTypeParameterElement> typeParameters = new ArrayList<>();

  public TreeBackedParameterizable(
      Element underlyingElement,
      TreeBackedElement enclosingElement,
      @Nullable Tree tree,
      TreeBackedElementResolver resolver) {
    super(underlyingElement, enclosingElement, tree, resolver);
  }

  /* package */ void addTypeParameter(TreeBackedTypeParameterElement typeParameter) {
    typeParameters.add(typeParameter);
  }

  @Override
  public List<? extends TypeParameterElement> getTypeParameters() {
    return Collections.unmodifiableList(typeParameters);
  }
}
