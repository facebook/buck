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
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.stream.Stream;


/**
 * Common implementation of RustLinkable methods.
 */
public class RustLinkables {
  private RustLinkables() {
  }

  public static String ruleToCrateName(String rulename) {
    return rulename.replace('-', '_');
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

  private static Stream<Arg> getNativeArgs(
      Iterable<BuildRule> deps,
      Linker.LinkableDepType linkStyle,
      CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    return NativeLinkables.getTransitiveNativeLinkableInput(
            cxxPlatform,
            deps,
            linkStyle,
            RustLinkable.class::isInstance)
        .getArgs()
        .stream();
  }

  static void accumNativeArgs(
      Iterable<BuildRule> deps,
      Linker.LinkableDepType linkStyle,
      CxxPlatform cxxPlatform,
      ImmutableList.Builder<String> arglist) throws NoSuchBuildTargetException {
    getNativeArgs(deps, linkStyle, cxxPlatform)
        .forEach(arg -> arg.appendToCommandLine(arglist));
  }

  static Stream<Path> getNativePaths(
      Iterable<BuildRule> deps,
      Linker.LinkableDepType linkStyle,
      CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    // This gets the dependency as a stream of Arg objects. These represent options
    // to be passed to the linker, but we really just want the pathnames. We can identify
    // those by checking for implementors of HasSourcePath. This has the effect of dropping
    // the other options like --whole-archive which may cause linker errors if required.
    return getNativeArgs(deps, linkStyle, cxxPlatform)
        .filter(arg -> arg instanceof HasSourcePath)
        .map(arg -> (HasSourcePath) arg)
        .map(
            arg -> {
              SourcePath sourcePath = arg.getPath();
              SourcePathResolver sourcePathResolver = arg.getPathResolver();
              return sourcePathResolver.getAbsolutePath(sourcePath);
            });
  }

  static ImmutableSet<Path> getNativeDirs(
      Iterable<BuildRule> deps,
      Linker.LinkableDepType linkStyle,
      CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    return getNativePaths(deps, linkStyle, cxxPlatform)
        .map(Path::getParent)
        .collect(MoreCollectors.toImmutableSet());
  }

  static BuildRuleParams addNativeDependencies(
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType linkStyle) {
    return params.appendExtraDeps(
        () -> {
          try {
            return getNativeArgs(params.getDeps(), linkStyle, cxxPlatform)
                .flatMap(arg -> arg.getDeps(ruleFinder).stream())
                .collect(MoreCollectors.toImmutableList());
          } catch (NoSuchBuildTargetException e) {
            throw new RuntimeException(e);
          }
        });
  }

  public static ImmutableList<String> extendLinkerArgs(
      ImmutableList<String> linkerArgs,
      Iterable<BuildRule> deps,
      Linker.LinkableDepType linkStyle,
      CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    builder.addAll(linkerArgs);
    RustLinkables.accumNativeArgs(deps, linkStyle, cxxPlatform, builder);

    return builder.build();
  }
}
