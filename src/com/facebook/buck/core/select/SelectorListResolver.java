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

package com.facebook.buck.core.select;

import com.facebook.buck.core.exceptions.DependencyStack;

/**
 * A resolver that analyzes {@link SelectorList}, evaluates selectable statements and constructs the
 * final value that is produced by concatenating selected values (the type of the elements
 * determines the process of concatenation.)
 */
public interface SelectorListResolver {

  <T> SelectorListResolved<T> resolveSelectorList(
      SelectorList<T> selectorList, DependencyStack dependencyStack);
}
