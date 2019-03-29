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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.arg.HasProvidedDepsQuery;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.rules.query.Query;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Holds dependencies from a {@link JavaLibraryDescription.CoreArg} after they've been resolved from
 * {@link BuildTarget}s to {@link BuildRule}s, including resolving queries and (TODO:jkeljo)
 * exports.
 */
@Value.Immutable
@Value.Style(
    overshadowImplementation = true,
    init = "set*",
    visibility = Value.Style.ImplementationVisibility.PACKAGE)
public abstract class JavaLibraryDeps {
  public static JavaLibraryDeps newInstance(
      JavaLibraryDescription.CoreArg args,
      BuildRuleResolver resolver,
      TargetConfiguration targetConfiguration,
      ConfiguredCompilerFactory compilerFactory) {
    Builder builder =
        new Builder(resolver)
            .setDepTargets(args.getDeps())
            .setExportedDepTargets(args.getExportedDeps())
            .setProvidedDepTargets(args.getProvidedDeps())
            .setExportedProvidedDepTargets(args.getExportedProvidedDeps())
            .setSourceOnlyAbiDepTargets(args.getSourceOnlyAbiDeps());
    compilerFactory.getNonProvidedClasspathDeps(targetConfiguration, builder::addDepTargets);

    if (args instanceof HasDepsQuery) {
      builder.setDepsQuery(((HasDepsQuery) args).getDepsQuery());
    }
    if (args instanceof HasProvidedDepsQuery) {
      builder.setProvidedDepsQuery(((HasProvidedDepsQuery) args).getProvidedDepsQuery());
    }
    return builder.build();
  }

  @org.immutables.builder.Builder.Parameter
  abstract BuildRuleResolver getResolver();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<BuildTarget> getDepTargets();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<BuildTarget> getProvidedDepTargets();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<BuildTarget> getExportedDepTargets();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<BuildTarget> getExportedProvidedDepTargets();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<BuildTarget> getSourceOnlyAbiDepTargets();

  abstract Optional<Query> getDepsQuery();

  abstract Optional<Query> getProvidedDepsQuery();

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getDeps() {
    return resolve(
        Iterables.concat(
            getDepTargets(),
            getExportedDepTargets(),
            getDepsQuery().map(Query::getResolvedQuery).orElse(ImmutableSortedSet.of())));
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getProvidedDeps() {
    return resolve(
        Iterables.concat(
            getProvidedDepTargets(),
            getExportedProvidedDepTargets(),
            getProvidedDepsQuery().map(Query::getResolvedQuery).orElse(ImmutableSortedSet.of())));
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return resolve(getExportedDepTargets());
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getExportedProvidedDeps() {
    return resolve(getExportedProvidedDepTargets());
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getSourceOnlyAbiDeps() {
    return resolve(getSourceOnlyAbiDepTargets());
  }

  private ImmutableSortedSet<BuildRule> resolve(Iterable<BuildTarget> targets) {
    return getResolver().getAllRules(targets);
  }

  public static class Builder extends ImmutableJavaLibraryDeps.Builder {
    Builder() {
      throw new UnsupportedOperationException();
    }

    public Builder(BuildRuleResolver resolver) {
      super(resolver);
    }
  }
}
