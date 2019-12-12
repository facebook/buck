/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.sun.source.util.TreePath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.Element;

abstract class TreeBackedParameterizable extends TreeBackedElement
    implements ArtificialParameterizable {
  private final List<TreeBackedTypeParameterElement> typeParameters = new ArrayList<>();

  public TreeBackedParameterizable(
      Element underlyingElement,
      TreeBackedElement enclosingElement,
      @Nullable TreePath treePath,
      PostEnterCanonicalizer canonicalizer) {
    super(underlyingElement, enclosingElement, treePath, canonicalizer);
  }

  /* package */ void addTypeParameter(TreeBackedTypeParameterElement typeParameter) {
    typeParameters.add(typeParameter);
  }

  @Override
  public List<TreeBackedTypeParameterElement> getTypeParameters() {
    return Collections.unmodifiableList(typeParameters);
  }
}
