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

import static com.facebook.buck.cxx.CxxDescriptionEnhancer.normalizeModuleName;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.nio.file.Path;
import java.util.Optional;
import org.stringtemplate.v4.ST;

public final class HeaderSymlinkTreeWithHeaderMap extends HeaderSymlinkTree {

  private static final Logger LOG = Logger.get(HeaderSymlinkTreeWithHeaderMap.class);

  private static final String MODULE_MAP = "buck.modulemap";
  private static final String MODULEMAP_TEMPLATE_PATH = getTemplate("modulemap.st");

  private static String getTemplate(String template) {
    try {
      return Resources.toString(
          Resources.getResource(HeaderSymlinkTreeWithHeaderMap.class, template), Charsets.UTF_8);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  @AddToRuleKey(stringify = true)
  private final Path headerMapPath;

  @AddToRuleKey private final boolean shouldCreateModule;

  private HeaderSymlinkTreeWithHeaderMap(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      SourcePathRuleFinder ruleFinder,
      Path headerMapPath,
      boolean shouldCreateModule) {
    super(target, filesystem, root, links, ruleFinder);
    this.headerMapPath = headerMapPath;
    this.shouldCreateModule = shouldCreateModule;
  }

  public static HeaderSymlinkTreeWithHeaderMap create(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      SourcePathRuleFinder ruleFinder) {
    Path headerMapPath = getPath(filesystem, target);
    boolean shouldCreateModule =
        target.getFlavors().contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);
    return new HeaderSymlinkTreeWithHeaderMap(
        target, filesystem, root, links, ruleFinder, headerMapPath, shouldCreateModule);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), headerMapPath);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    LOG.debug("Generating post-build steps to write header map to %s", headerMapPath);
    Path buckOut =
        getProjectFilesystem().resolve(getProjectFilesystem().getBuckPaths().getBuckOut());

    ImmutableMap.Builder<Path, Path> headerMapEntries = ImmutableMap.builder();
    for (Path key : getLinks().keySet()) {
      // The key is the path that will be referred to in headers. It can be anything. However, the
      // value given in the headerMapEntries is the path of that entry in the generated symlink
      // tree. Because "reasons", we don't want to cache that value, so we need to relativize the
      // path to the output directory of this current rule. We then rely on magic and the stars
      // aligning in order to get this to work. May we find peace in another life.
      headerMapEntries.put(key, buckOut.relativize(getRoot().resolve(key)));
    }
    ImmutableList.Builder<Step> builder =
        ImmutableList.<Step>builder()
            .addAll(super.getBuildSteps(context, buildableContext))
            .add(
                new HeaderMapStep(getProjectFilesystem(), headerMapPath, headerMapEntries.build()));

    if (shouldCreateModule) {
      Optional<String> umbrellaHeader = getUmbrellaHeader(getBuildTarget().getShortName());
      String moduleName = normalizeModuleName(getBuildTarget().getShortName());
      builder.add(MkdirStep.of(getProjectFilesystem(), getRoot().resolve(moduleName)));
      builder.add(createCreateModuleStep(moduleName, umbrellaHeader));
    }
    return builder.build();
  }

  /**
   * If any header file name matches the name of the target, it will be implicitly used as the
   * umbrella header of the module of that target.
   */
  private Optional<String> getUmbrellaHeader(String moduleName) {
    return getLinks()
        .keySet()
        .stream()
        .filter(input -> moduleName.equals(MorePaths.getNameWithoutExtension(input)))
        .map(Path::toString)
        .findFirst();
  }

  private Step createCreateModuleStep(String moduleName, Optional<String> umbrellaHeader) {
    ST st =
        new ST(MODULEMAP_TEMPLATE_PATH)
            .add("module_name", moduleName)
            .add("use_umbrella_header", umbrellaHeader.isPresent());
    if (umbrellaHeader.isPresent()) {
      st.add("umbrella_header_name", umbrellaHeader.get());
    } else {
      st.add("umbrella_directory", moduleName);
    }
    return new WriteFileStep(
        getProjectFilesystem(),
        st.render(),
        getProjectFilesystem().relativize(getRoot().resolve(MODULE_MAP)),
        false);
  }

  @Override
  public Path getIncludePath() {
    return getProjectFilesystem().resolve(getProjectFilesystem().getBuckPaths().getBuckOut());
  }

  @Override
  public Optional<Path> getHeaderMap() {
    return Optional.of(getProjectFilesystem().resolve(headerMapPath));
  }

  @VisibleForTesting
  static Path getPath(ProjectFilesystem filesystem, BuildTarget target) {
    return BuildTargets.getGenPath(filesystem, target, "%s.hmap");
  }
}
