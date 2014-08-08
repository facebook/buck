/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * A {@link com.facebook.buck.rules.BuildRule} representing the contents and namespace layout
 * of a group of headers.  Other rules can depend on this rule to have rule key changes triggered
 * by header layout or content changes.
 */
public class CxxHeader extends AbstractBuildRule {

  private final ImmutableMap<Path, SourcePath> headers;

  public CxxHeader(
      BuildRuleParams params,
      ImmutableMap<Path, SourcePath> headers) {
    super(params);
    this.headers = Preconditions.checkNotNull(headers);
  }

  /**
   * We <b>do</b> have inputs, but we want to make sure hash them into the rule key along with
   * the path with which they get laid out in the header namespace.  So we return nothing here
   * and make sure to hash the input contents in {@link #appendDetailsToRuleKey}.
   */
  @Override
  protected Iterable<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  /**
   * We make sure to hash the header namespace (i.e. as it would appear to #include pragmas) along
   * with the contents of the header file.  If the relationship between either changes, we need
   * to trigger a change in the RuleKey.
   */
  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    for (ImmutableMap.Entry<Path, SourcePath> entry : headers.entrySet()) {
      builder.setInput("header(" + entry.getKey() + ")", entry.getValue().resolve());
    }
    return builder;
  }

  /**
   * @return an empty list, as no actions need to be performed.
   */
  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  /**
   * @return null, as no output needs to be produced.
   */
  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

}
