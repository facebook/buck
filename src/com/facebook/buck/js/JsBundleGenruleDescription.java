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

package com.facebook.buck.js;

import com.facebook.buck.apple.AppleBundleResources;
import com.facebook.buck.apple.HasAppleBundleResourcesDescription;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.shell.ExportFile;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

public class JsBundleGenruleDescription
    extends AbstractGenruleDescription<JsBundleGenruleDescriptionArg>
    implements Flavored, HasAppleBundleResourcesDescription<JsBundleGenruleDescriptionArg> {

  @Override
  public Class<JsBundleGenruleDescriptionArg> getConstructorArgType() {
    return JsBundleGenruleDescriptionArg.class;
  }

  @Override
  protected BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      JsBundleGenruleDescriptionArg args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe) {
    ImmutableSortedSet<Flavor> flavors = buildTarget.getFlavors();
    BuildTarget bundleTarget = args.getJsBundle().withAppendedFlavors(flavors);
    BuildRule jsBundle = resolver.requireRule(bundleTarget);

    if (flavors.contains(JsFlavors.SOURCE_MAP) || flavors.contains(JsFlavors.DEPENDENCY_FILE)) {
      // SOURCE_MAP is a special flavor that allows accessing the written source map, typically
      // via export_file in reference mode
      // DEPENDENCY_FILE is a special flavor that triggers building a single file (format defined by the worker)

      SourcePath output =
          args.getRewriteSourcemap() && flavors.contains(JsFlavors.SOURCE_MAP)
              ? ((JsBundleOutputs)
                      resolver.requireRule(buildTarget.withoutFlavors(JsFlavors.SOURCE_MAP)))
                  .getSourcePathToSourceMap()
              : Preconditions.checkNotNull(
                  jsBundle.getSourcePathToOutput(), "%s has no output", jsBundle.getBuildTarget());

      Path fileName =
          DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver))
              .getRelativePath(output)
              .getFileName();
      return new ExportFile(
          buildTarget,
          projectFilesystem,
          params,
          fileName.toString(),
          ExportFileDescription.Mode.REFERENCE,
          output);
    }

    if (!(jsBundle instanceof JsBundleOutputs)) {
      throw new HumanReadableException(
          "The 'js_bundle' argument of %s, %s, must correspond to a js_bundle() rule.",
          buildTarget, bundleTarget);
    }

    return new JsBundleGenrule(
        buildTarget,
        projectFilesystem,
        params.withExtraDeps(ImmutableSortedSet.of(jsBundle)),
        args,
        cmd,
        bash,
        cmdExe,
        (JsBundleOutputs) jsBundle);
  }

  @Override
  public void addAppleBundleResources(
      AppleBundleResources.Builder builder,
      TargetNode<JsBundleGenruleDescriptionArg, ?> targetNode,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver) {
    JsBundleGenrule genrule =
        resolver.getRuleWithType(targetNode.getBuildTarget(), JsBundleGenrule.class);
    JsBundleDescription.addAppleBundleResources(builder, genrule);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return JsBundleDescription.supportsFlavors(flavors);
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(JsBundleDescription.FLAVOR_DOMAINS);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJsBundleGenruleDescriptionArg extends AbstractGenruleDescription.CommonArg {
    BuildTarget getJsBundle();

    @Override
    default String getOut() {
      return JsBundleOutputs.JS_DIR_NAME;
    }

    @Value.Default
    default boolean getRewriteSourcemap() {
      return false;
    }

    @Override
    default Optional<String> getType() {
      return Optional.of("js_bundle");
    }
  }
}
