/*
 * Copyright 2018-present Facebook, Inc.
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


package com.facebook.buck.android;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Optional;

public class NoDxArgsHelper {

  private static final Logger LOG = Logger.get(NoDxArgsHelper.class);

  static ImmutableSortedSet<JavaLibrary> findRulesToExcludeFromDex(
      ActionGraphBuilder graphBuilder,
      BuildTarget buildTarget,
      ImmutableSet<BuildTarget> noDxTargets) {
    // Build rules added to "no_dx" are only hints, not hard dependencies. Therefore, although a
    // target may be mentioned in that parameter, it may not be present as a build rule.
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    for (BuildTarget noDxTarget : noDxTargets) {
      Optional<BuildRule> ruleOptional = graphBuilder.getRuleOptional(noDxTarget);
      if (ruleOptional.isPresent()) {
        builder.add(ruleOptional.get());
      } else {
        LOG.info("%s: no_dx target not a dependency: %s", buildTarget, noDxTarget);
      }
    }

    ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex = builder.build();
    return RichStream.from(buildRulesToExcludeFromDex)
        .filter(JavaLibrary.class)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }
}
