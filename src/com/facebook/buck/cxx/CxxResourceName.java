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

package com.facebook.buck.cxx;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import java.util.Objects;

/**
 * The fully-qualified name of a C++ resource, determining how C++ can locate it via the generated
 * resource JSON file.
 */
public final class CxxResourceName implements AddsToRuleKey {

  // Stringify to avoid unsupported field error when adding to rule key.
  @AddToRuleKey(stringify = true)
  private final ForwardRelPath name;

  public CxxResourceName(ForwardRelPath name) {
    this.name = name;
  }

  public ForwardRelPath getNameAsPath() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CxxResourceName that = (CxxResourceName) o;
    return name.equals(that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), name);
  }
}
