/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.d;

import com.facebook.buck.cxx.Tool;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableList;

public class DLibrary extends DLinkable {
  public DLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableList<SourcePath> inputs,
      Tool compiler) {
    super(
        params,
        resolver,
        inputs,
        ImmutableList.of("-lib"),
        BuildTargets.getGenPath(
            params.getBuildTarget(), "%s/lib" + params.getBuildTarget().getShortName() + ".a"),
        compiler);
  }

  @Override
  public BuildableProperties getProperties() {
    return new BuildableProperties(BuildableProperties.Kind.LIBRARY);
  }
}
