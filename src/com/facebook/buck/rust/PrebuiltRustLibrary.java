/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rust;

import com.facebook.buck.rules.AbstractBuildRuleWithResolver;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ForwardingBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;

/**
 * A pre-built rust library (ie, typically .rlib). This can't be a NoopBuildRule because we need to
 * implement getPathToOutput(). Note that the same library file is used for all build modes, so the
 * library should be a static .rlib compile with PIC relocation so that its compatible with all
 * other modes. Later we may want to allow per-flavor files.
 */
abstract class PrebuiltRustLibrary extends AbstractBuildRuleWithResolver implements RustLinkable {

  public PrebuiltRustLibrary(BuildRuleParams params, SourcePathResolver resolver) {
    super(params, resolver);
  }

  /**
   * Get the name of the pre-built Rust library (typically with a .rlib extension)
   *
   * @return Path to prebuilt library.
   */
  protected abstract SourcePath getRlib();

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ForwardingBuildTargetSourcePath(getBuildTarget(), getRlib());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("rlib", getRlib());
  }
}
