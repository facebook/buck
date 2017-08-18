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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Optionals;
import com.google.common.collect.ImmutableCollection;
import java.util.Objects;

/** A base class for {@link Arg}s which wrap a {@link FrameworkPath}. */
abstract class FrameworkPathArg implements Arg {

  protected final ImmutableCollection<FrameworkPath> frameworkPaths;

  public FrameworkPathArg(ImmutableCollection<FrameworkPath> frameworkPaths) {
    this.frameworkPaths = frameworkPaths;
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(final SourcePathRuleFinder ruleFinder) {
    return frameworkPaths
        .stream()
        .map(FrameworkPath::getSourcePath)
        .flatMap(Optionals::toStream)
        .flatMap(ruleFinder.FILTER_BUILD_RULE_INPUTS)
        .collect(MoreCollectors.toImmutableSet());
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return frameworkPaths
        .stream()
        .map(FrameworkPath::getSourcePath)
        .flatMap(Optionals::toStream)
        .collect(MoreCollectors.toImmutableList());
  }

  @Override
  public String toString() {
    return frameworkPaths.toString();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof FrameworkPathArg)) {
      return false;
    }
    return ((FrameworkPathArg) other).frameworkPaths.equals(frameworkPaths);
  }

  @Override
  public int hashCode() {
    return Objects.hash(frameworkPaths);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("frameworkPaths", frameworkPaths);
  }
}
