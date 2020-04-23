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

package com.facebook.buck.core.linkgroup;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.google.common.annotations.VisibleForTesting;
import java.util.regex.Pattern;

/** Target matcher for regex labels beginning with "label:". */
public class CxxLinkGroupMappingTargetLabelMatcher implements CxxLinkGroupMappingTargetMatcher {

  @VisibleForTesting public Pattern labelPattern;

  public CxxLinkGroupMappingTargetLabelMatcher(Pattern labelPattern) {
    this.labelPattern = labelPattern;
  }

  @Override
  public boolean matchesNode(TargetNode<?> node) {
    boolean matchesRegex = false;
    if (node.getConstructorArg() instanceof BuildRuleArg) {
      BuildRuleArg buildRuleArg = (BuildRuleArg) node.getConstructorArg();
      for (String label : buildRuleArg.getLabels()) {
        matchesRegex = labelPattern.matcher(label).matches();
        if (matchesRegex) {
          break;
        }
      }
    }
    return matchesRegex;
  }

  @Override
  public int compareTo(CxxLinkGroupMappingTargetMatcher that) {
    if (this == that) {
      return 0;
    }

    if (that instanceof CxxLinkGroupMappingTargetLabelMatcher) {
      return this.labelPattern
          .pattern()
          .compareTo(((CxxLinkGroupMappingTargetLabelMatcher) that).labelPattern.pattern());
    }

    return this.getClass().getName().compareTo(that.getClass().getName());
  }
}
