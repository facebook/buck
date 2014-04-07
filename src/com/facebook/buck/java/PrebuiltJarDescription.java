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

package com.facebook.buck.java;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class PrebuiltJarDescription implements Description<PrebuiltJarDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("prebuilt_jar");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public PrebuiltJar createBuildable(BuildRuleParams params, Arg args) {
    return new PrebuiltJar(params, args.binaryJar, args.sourceJar, args.javadocUrl);
  }

  public static class Arg implements ConstructorArg {
    public Path binaryJar;
    public Optional<Path> sourceJar;
    public Optional<String> javadocUrl;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
