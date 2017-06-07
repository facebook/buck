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

package com.facebook.buck.rules.args;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import java.util.Objects;

/**
 * An {@link Arg} which must be sanitized before contributing to a {@link
 * com.facebook.buck.rules.RuleKey}.
 *
 * <p>
 *
 * <pre>{@code
 * ImmutableMap<String, String> toolchainRoots =
 *     ImmutableMap.of("/opt/toolchain", "$TOOLCHAIN_ROOT");
 * Path toolchainRoot = Paths.get("/opt/toolchain");
 * Arg arg =
 *     new SanitizedArg(
 *         Functions.forMap(toolchainRoots),
 *         "/opt/toolchain/bin/tool");
 * }</pre>
 */
public class SanitizedArg implements Arg {

  private final Function<? super String, String> sanitizer;
  private final String unsanitzed;

  public SanitizedArg(Function<? super String, String> sanitizer, String unsanitzed) {
    this.sanitizer = sanitizer;
    this.unsanitzed = unsanitzed;
  }

  @Override
  public void appendToCommandLine(
      ImmutableCollection.Builder<String> builder, SourcePathResolver pathResolver) {
    builder.add(unsanitzed);
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return ImmutableList.of();
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return ImmutableList.of();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("arg", sanitizer.apply(unsanitzed));
  }

  @Override
  public String toString() {
    return unsanitzed;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SanitizedArg)) {
      return false;
    }
    SanitizedArg sanitizedArg = (SanitizedArg) o;
    return Objects.equals(sanitizer, sanitizedArg.sanitizer)
        && Objects.equals(unsanitzed, sanitizedArg.unsanitzed);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sanitizer, unsanitzed);
  }

  public static ImmutableList<Arg> from(Function<String, String> sanitizer, Iterable<String> args) {
    ImmutableList.Builder<Arg> converted = ImmutableList.builder();
    for (String arg : args) {
      converted.add(new SanitizedArg(sanitizer, arg));
    }
    return converted.build();
  }
}
