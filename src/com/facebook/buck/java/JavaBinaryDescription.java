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
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class JavaBinaryDescription implements Description<JavaBinaryDescription.Args> {

  public static final BuildRuleType TYPE = new BuildRuleType("java_binary");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Args createUnpopulatedConstructorArg() {
    return new Args();
  }

  @Override
  public Buildable createBuildable(
      BuildRuleParams params, Args args) {
    return new JavaBinary(
        params.getBuildTarget(),
        args.deps.get(),
        args.mainClass.orNull(),
        args.manifestFile.orNull(),
        args.mergeManifests.or(true),
        args.metaInfDirectory.orNull(),
        new DefaultDirectoryTraverser());
  }

  public static class Args implements ConstructorArg {
    public Optional<ImmutableSortedSet<BuildRule>> deps;
    public Optional<String> mainClass;
    public Optional<SourcePath> manifestFile;
    public Optional<Boolean> mergeManifests;
    @Beta
    public Optional<Path> metaInfDirectory;
  }
}
