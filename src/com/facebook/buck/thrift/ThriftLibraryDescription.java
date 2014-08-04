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

package com.facebook.buck.thrift;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.thrift.ThriftLibraryDescription.Arg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableSortedSet;

public class ThriftLibraryDescription implements Description<Arg> {
  public static final BuildRuleType TYPE = new BuildRuleType("thrift_library");

  public static class Arg implements ConstructorArg {
    public String name;
    public ImmutableSortedSet<SourcePath> srcs;
  }

  @Override
  public <A extends Arg> ThriftLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return new ThriftLibrary(params, args.srcs);
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

}
