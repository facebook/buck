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

package com.facebook.buck.lua;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LuaBinaryDescription implements Description<LuaBinaryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("lua_binary");

  private final LuaConfig luaConfig;

  public LuaBinaryDescription(LuaConfig luaConfig) {
    this.luaConfig = luaConfig;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  private Iterable<LuaPackageComponents> getPackageComponentsFromDeps(Iterable<BuildRule> deps) {
    final List<LuaPackageComponents> components = new ArrayList<>();
    new AbstractBreadthFirstTraversal<BuildRule>(deps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof LuaPackageable) {
          components.add(((LuaPackageable) rule).getLuaPackageComponents());
          return rule.getDeps();
        }
        return ImmutableSet.of();
      }
    }.start();
    return components;
  }

  private SymlinkTree createSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      LuaPackageComponents components) {
    BuildTarget linkTreeTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("link-tree"))
            .build();
    Path linkTreeRoot = params.getProjectFilesystem().resolve(
        BuildTargets.getGenPath(linkTreeTarget, "%s"));
    try {
      return resolver.addToIndex(
          SymlinkTree.from(
              params.copyWithChanges(
                  linkTreeTarget,
                  Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                  Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
              pathResolver,
              linkTreeRoot,
              ImmutableMap.<String, SourcePath>builder()
                  .putAll(components.getModules())
                  .putAll(components.getNativeLibraries())
                  .build()));
    } catch (SymlinkTree.InvalidSymlinkTreeException e) {
      throw e.getHumanReadableExceptionForBuildTarget(
          params.getBuildTarget().getUnflavoredBuildTarget());
    }
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args)
      throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    LuaPackageComponents allComponents =
        LuaPackageComponents.concat(getPackageComponentsFromDeps(params.getDeps()));
    SymlinkTree linkTree =
        resolver.addToIndex(createSymlinkTree(params, resolver, pathResolver, allComponents));
    return new LuaInPlaceBinary(
        params,
        pathResolver,
        luaConfig.getLua(resolver),
        args.mainModule,
        allComponents,
        linkTree,
        luaConfig.getExtension());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public String mainModule;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}
