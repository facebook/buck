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

package com.facebook.buck.python;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class PythonBinaryDescription implements Description<PythonBinaryDescription.Arg> {

  private static final BuildRuleType TYPE = new BuildRuleType("python_binary");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public Buildable createBuildable(BuildRuleParams params, Arg args) {
    // It's safe to do this, since we know that the arg marshaller fills in optional sets as empty
    // sets. Because we're nice like that.
    return new PythonBinary(args.deps.get(), args.main);
  }

  public static class Arg implements ConstructorArg {
    public Path main;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
