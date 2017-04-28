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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class ApkGenruleDescription extends AbstractGenruleDescription<ApkGenruleDescription.Arg> {

  @Override
  public Class<Arg> getConstructorArgType() {
    return Arg.class;
  }

  @Override
  protected BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      Arg args,
      Optional<com.facebook.buck.rules.args.Arg> cmd,
      Optional<com.facebook.buck.rules.args.Arg> bash,
      Optional<com.facebook.buck.rules.args.Arg> cmdExe) {

    final BuildRule apk = resolver.getRule(args.apk);
    if (!(apk instanceof HasInstallableApk)) {
      throw new HumanReadableException(
          "The 'apk' argument of %s, %s, must correspond to an "
              + "installable rule, such as android_binary() or apk_genrule().",
          params.getBuildTarget(), args.apk.getFullyQualifiedName());
    }
    HasInstallableApk installableApk = (HasInstallableApk) apk;

    final Supplier<ImmutableSortedSet<BuildRule>> originalExtraDeps = params.getExtraDeps();

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    return new ApkGenrule(
        params.copyReplacingExtraDeps(
            Suppliers.memoize(
                () ->
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(originalExtraDeps.get())
                        .add(installableApk)
                        .build())),
        ruleFinder,
        args.srcs,
        cmd,
        bash,
        cmdExe,
        args.type.isPresent() ? args.type : Optional.of("apk"),
        installableApk.getSourcePathToOutput());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractGenruleDescription.Arg {
    public BuildTarget apk;
  }
}
