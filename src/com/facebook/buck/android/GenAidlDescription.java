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

package com.facebook.buck.android;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.nio.file.Path;

public class GenAidlDescription implements Description<GenAidlDescription.Arg> {

  public static BuildRuleType TYPE = new BuildRuleType("gen_aidl");

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
    // import_path is an anomaly: it is a path that is relative to the project root rather than
    // relative to the build file directory.
    File importPath = new File(args.importPath);
    if (!importPath.isDirectory()) {
      throw new RuntimeException("Directory does not exist: " + importPath.getAbsolutePath());
    }

    return new GenAidl(params.getBuildTarget(), args.aidl, args.importPath);
  }

  public static class Arg {
    public Path aidl;
    @Hint(name = "import_path")
    public String importPath;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
