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

package com.facebook.buck.jvm.java.lang.model;

import com.facebook.buck.util.liteinfersupport.Nullable;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * Wraps and extends {@link javax.lang.model.util.Elements} with methods that cannot be added as
 * pure extension methods on {@link MoreElements} because they require per-instance state.
 */
public interface ElementsExtended extends Elements {
  List<ExecutableElement> getDeclaredMethods(TypeElement owner, CharSequence name);

  List<ExecutableElement> getAllMethods(TypeElement owner, CharSequence name);

  List<BridgeMethod> getBridgeMethods(TypeElement owner, CharSequence name);

  List<BridgeMethod> getAllBridgeMethods(TypeElement type);

  @Nullable
  ExecutableElement getImplementation(ExecutableElement method, TypeElement inType);

  @Nullable
  TypeElement getBinaryImplementationOwner(ExecutableElement method, TypeElement inType);

  boolean isCompiledInCurrentRun(Element element);
}
