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

package com.facebook.buck.shell;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class ShBinaryDescription implements Description<ShBinaryDescription.Arg> {

  private static final BuildRuleType TYPE = new BuildRuleType("sh_binary");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public ShBinary createBuildable(BuildRuleParams params, Arg args) {
    // It's safe to do this, since we know that the arg marshaller fills in optional sets as empty
    // sets. Because we're nice like that.
    return new ShBinary(params.getBuildTarget(), args.main, args.resources.get());
  }

  public static class Arg implements ConstructorArg {
    public SourcePath main;

    public Optional<ImmutableSortedSet<BuildRule>> deps;

    public Optional<ImmutableSet<SourcePath>> resources;
  }
}
