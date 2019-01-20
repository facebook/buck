/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.features.apple.project;

import com.facebook.buck.apple.AppleDescriptions;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.cxx.CxxLibraryDescription;
import java.util.IdentityHashMap;

public class ProjectGenerationStateCache {
  private final IdentityHashMap<TargetNode<?>, Boolean> targetSwiftCodeCache =
      new IdentityHashMap<TargetNode<?>, Boolean>();

  public boolean targetContainsSwiftSourceCode(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode) {
    Boolean containsSwiftCode = targetSwiftCodeCache.get(targetNode);
    if (containsSwiftCode == null) {
      containsSwiftCode = AppleDescriptions.targetNodeContainsSwiftSourceCode(targetNode);
      targetSwiftCodeCache.put(targetNode, containsSwiftCode);
    }

    return containsSwiftCode.booleanValue();
  }
}
