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

package com.facebook.buck.apple;

import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

/**
 * Description for an ios_postprocess_resources rule which runs a shell script
 * after the 'copy resources' phase has run.
 */
public class IosPostprocessResourcesDescription
  implements Description<IosPostprocessResourcesDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("ios_postprocess_resources");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> IosPostprocessResources createBuildRule(
    BuildRuleParams params,
    BuildRuleResolver resolver,
    A args) {
    return new IosPostprocessResources(
      params,
      /* srcs */ ImmutableList.<SourcePath>of(),
      args.cmd,
      /* bash */ Optional.<String>absent(),
      /* cmdExe */ Optional.<String>absent(),
      /* out */ "",
      params.getPathAbsolutifier());
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<String> cmd;
  }
}
