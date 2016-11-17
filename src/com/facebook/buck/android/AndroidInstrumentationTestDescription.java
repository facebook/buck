/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class AndroidInstrumentationTestDescription
    implements Description<AndroidInstrumentationTestDescription.Arg> {

  private final JavaOptions javaOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public AndroidInstrumentationTestDescription(
      JavaOptions javaOptions,
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.javaOptions = javaOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> AndroidInstrumentationTest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    BuildRule apk = resolver.getRule(args.apk);
    if (!(apk instanceof InstallableApk)) {
      throw new HumanReadableException(
          "In %s, instrumentation_apk='%s' must be an android_binary(), apk_genrule() or " +
          "android_instrumentation_apk(), but was %s().",
          params.getBuildTarget(),
          apk.getFullyQualifiedName(),
          apk.getType());
    }

    return new AndroidInstrumentationTest(
        params.appendExtraDeps(
            BuildRules.getExportedRules(
                params.getDeclaredDeps().get())),
        new SourcePathResolver(resolver),
        (InstallableApk) apk,
        args.labels,
        args.contacts,
        javaOptions.getJavaRuntimeLauncher(),
        args.testRuleTimeoutMs.map(Optional::of).orElse(defaultTestRuleTimeoutMs));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public BuildTarget apk;
    public ImmutableSortedSet<Label> labels = ImmutableSortedSet.of();
    public ImmutableSortedSet<String> contacts = ImmutableSortedSet.of();
    public Optional<Long> testRuleTimeoutMs;
  }

}
