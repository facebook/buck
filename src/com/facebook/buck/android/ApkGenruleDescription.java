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
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class ApkGenruleDescription extends AbstractGenruleDescription<ApkGenruleDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("apk_genrule");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  protected <A extends ApkGenruleDescription.Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      ImmutableList<SourcePath> srcs,
      Function<String, String> macroExpander,
      Optional<String> cmd,
      Optional<String> bash,
      Optional<String> cmdExe,
      String out,
      Function<Path, Path> relativeToAbsolutePathFunction,
      Supplier<ImmutableList<Object>> macroRuleKeyAppendables) {

    final BuildRule installableApk = resolver.getRule(args.apk);
    if (!(installableApk instanceof InstallableApk)) {
      throw new HumanReadableException("The 'apk' argument of %s, %s, must correspond to an " +
          "installable rule, such as android_binary() or apk_genrule().",
          params.getBuildTarget(),
          args.apk.getFullyQualifiedName());
    }

    final Supplier<ImmutableSortedSet<BuildRule>> originalExtraDeps = params.getExtraDeps();

    return new ApkGenrule(
        params.copyWithExtraDeps(
            Suppliers.memoize(
                new Supplier<ImmutableSortedSet<BuildRule>>() {
                  @Override
                  public ImmutableSortedSet<BuildRule> get() {
                    return ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(originalExtraDeps.get())
                        .add(installableApk)
                        .build();
                  }
                })),
        new SourcePathResolver(resolver),
        srcs,
        macroExpander,
        cmd,
        bash,
        cmdExe,
        relativeToAbsolutePathFunction,
        macroRuleKeyAppendables,
        (InstallableApk) installableApk);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractGenruleDescription.Arg {
    public BuildTarget apk;
  }
}
