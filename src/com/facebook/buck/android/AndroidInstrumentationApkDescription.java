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
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

public class AndroidInstrumentationApkDescription
    implements Description<AndroidInstrumentationApkDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("android_instrumentation_apk");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public AndroidInstrumentationApk createBuildable(BuildRuleParams params, Arg args) {
    if (!(args.apk.getBuildable() instanceof InstallableApk)) {
      throw new HumanReadableException(
          "In %s, apk='%s' must be an android_binary() or apk_genrule() but was %s().",
          params.getBuildTarget(),
          args.apk.getFullyQualifiedName(),
          args.apk.getType().getName());
    }

    return new AndroidInstrumentationApk(
        params,
        args.manifest,
        getUnderlyingApk((InstallableApk) args.apk.getBuildable()),
        args.apk,
        args.deps.get());
  }

  private static AndroidBinary getUnderlyingApk(InstallableApk installable) {
    if (installable instanceof AndroidBinary) {
      return (AndroidBinary) installable;
    } else if (installable instanceof ApkGenrule) {
      return getUnderlyingApk(((ApkGenrule) installable).getInstallableApk());
    } else {
      throw new IllegalStateException(
          installable.getBuildTarget().getFullyQualifiedName() +
              " must be backed by either an android_binary() or an apk_genrule()");
    }
  }

  public static class Arg implements ConstructorArg {
    public SourcePath manifest;
    public BuildRule apk;
    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
