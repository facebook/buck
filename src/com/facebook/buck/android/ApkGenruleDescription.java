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

package com.facebook.buck.android;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class ApkGenruleDescription implements Description<ApkGenruleDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("apk_genrule");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> ApkGenrule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    if (!(args.apk instanceof InstallableApk)) {
      throw new HumanReadableException("The 'apk' argument of %s, %s, must correspond to an " +
          "installable rule, such as android_binary() or apk_genrule().",
          params.getBuildTarget(),
          args.apk.getFullyQualifiedName());
    }

    InstallableApk installableApk = (InstallableApk) args.apk;

    return new ApkGenrule(
        params,
        args.srcs.get(),
        args.cmd,
        args.bash,
        args.cmdExe,
        params.getPathAbsolutifier(),
        installableApk);
  }

  public static class Arg implements ConstructorArg {
    public BuildRule apk;

    public String out;
    public Optional<String> bash;
    public Optional<String> cmd;
    public Optional<String> cmdExe;
    public Optional<ImmutableList<Path>> srcs;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
