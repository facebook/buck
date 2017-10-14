/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.rules.keys.AbstractRuleKeyBuilder;
import com.facebook.buck.rules.keys.AlterRuleKeys;
import com.facebook.buck.rules.keys.RuleKeyScopedHasher;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Scope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public final class BuildableSupport {
  private BuildableSupport() {}

  public static Stream<BuildRule> deriveDeps(AddsToRuleKey rule, SourcePathRuleFinder ruleFinder) {
    Builder builder = new Builder(ruleFinder);
    AlterRuleKeys.amendKey(builder, rule);
    return builder.build();
  }

  public static Stream<BuildRule> deriveDeps(BuildRule rule, SourcePathRuleFinder ruleFinder) {
    Builder builder = new Builder(ruleFinder);
    AlterRuleKeys.amendKey(builder, rule);
    return builder.build();
  }

  /**
   * Creates a supplier to easily implement (and cache) BuildRule.getBuildDeps() via
   * BuildableSupport.deriveDeps().
   */
  public static Supplier<SortedSet<BuildRule>> buildDepsSupplier(
      BuildRule mergeThirdPartyJarResources, SourcePathRuleFinder ruleFinder) {
    return MoreSuppliers.memoize(
        () ->
            deriveDeps(mergeThirdPartyJarResources, ruleFinder)
                .collect(MoreCollectors.toImmutableSortedSet()));
  }

  private static class Builder extends AbstractRuleKeyBuilder<Stream<BuildRule>> {
    private final Stream.Builder<BuildRule> streamBuilder;
    private final SourcePathRuleFinder ruleFinder;

    public Builder(SourcePathRuleFinder ruleFinder) {
      super(
          new RuleKeyScopedHasher() {
            @Override
            public Scope keyScope(String key) {
              return () -> {};
            }

            @Override
            public Scope wrapperScope(RuleKeyHasher.Wrapper wrapper) {
              return () -> {};
            }

            @Override
            public ContainerScope containerScope(RuleKeyHasher.Container container) {
              return new ContainerScope() {
                @Override
                public void close() {}

                @Override
                public Scope elementScope() {
                  return () -> {};
                }
              };
            }
          });
      this.ruleFinder = ruleFinder;
      this.streamBuilder = Stream.builder();
    }

    @Override
    public RuleKeyObjectSink setPath(Path absolutePath, Path ideallyRelative) throws IOException {
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<BuildRule>> setSingleValue(@Nullable Object val) {
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<BuildRule>> setBuildRule(BuildRule rule) {
      streamBuilder.accept(rule);
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<BuildRule>> setAddsToRuleKey(AddsToRuleKey appendable) {
      AlterRuleKeys.amendKey(this, appendable);
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<BuildRule>> setSourcePath(SourcePath sourcePath)
        throws IOException {
      ruleFinder.getRule(sourcePath).ifPresent(streamBuilder);
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<BuildRule>> setNonHashingSourcePath(
        SourcePath sourcePath) {
      ruleFinder.getRule(sourcePath).ifPresent(streamBuilder);
      return this;
    }

    @Override
    public Stream<BuildRule> build() {
      return streamBuilder.build();
    }
  }
}
