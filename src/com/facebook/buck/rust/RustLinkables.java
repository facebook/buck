/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.rust;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.Comparator;


/**
 * Common implementation of RustLinkable methods.
 */
public class RustLinkables {
  private RustLinkables() {
  }

  static ImmutableSortedSet<Path> getDependencyPaths(BuildRule rule) {
    final ImmutableSortedSet.Builder<Path> builder = ImmutableSortedSet.naturalOrder();

    new AbstractBreadthFirstTraversal<BuildRule>(ImmutableSet.of(rule)) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof RustLinkable) {
          RustLinkable linkable = (RustLinkable) rule;
          builder.add(linkable.getLinkPath().getParent());
          return rule.getDeps();
        } else {
          return ImmutableSet.of();
        }
      }
    }.start();

    return builder.build();
  }

  private static ImmutableList<NativeLinkableInput> getNativeLinkableInputs(
      BuildRule top,
      Linker.LinkableDepType linkStyle,
      CxxPlatform cxxPlatform) {
    ImmutableList.Builder<NativeLinkableInput> builder = ImmutableList.builder();

    new AbstractBreadthFirstTraversal<BuildRule>(top) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof RustLinkable) {
          return rule.getDeps();
        }

        if (rule instanceof NativeLinkable) {
          try {
            NativeLinkableInput nli = NativeLinkables.getTransitiveNativeLinkableInput(
                cxxPlatform,
                ImmutableList.of(rule),
                linkStyle,
                Predicates.alwaysTrue());
            builder.add(nli);
          } catch (NoSuchBuildTargetException e) {
            e.printStackTrace();
          }
        }

        return ImmutableSet.of();
      }
    }.start();

    return builder.build();
  }

  static ImmutableSortedSet<Path> getNativePaths(
      Iterable<BuildRule> deps,
      SourcePathResolver resolver,
      Linker.LinkableDepType linkStyle,
      CxxPlatform cxxPlatform) {
    return FluentIterable.from(deps)
      .transformAndConcat(rule -> getNativeLinkableInputs(rule, linkStyle, cxxPlatform))
      .transformAndConcat(nli -> nli.getArgs())
      .transformAndConcat(arg -> arg.getDeps(resolver))
      .transform(dep -> dep.getPathToOutput())
      .toSortedSet(Comparator.<Path>naturalOrder());
  }

  static BuildRuleParams addNativeDependencies(
      BuildRuleParams params,
      SourcePathResolver resolver,
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType linkStyle) {
    return params.copyWithChanges(
        params.getBuildTarget(),
        () -> FluentIterable.from(params.getDeps())
            .transformAndConcat(rule -> getNativeLinkableInputs(rule, linkStyle, cxxPlatform))
            .transformAndConcat(nli -> nli.getArgs())
            .transformAndConcat(arg -> arg.getDeps(resolver))
            .append(params.getDeps())
            .toSortedSet(Ordering.natural()),
        Suppliers.ofInstance(ImmutableSortedSet.of()));
  }
}
