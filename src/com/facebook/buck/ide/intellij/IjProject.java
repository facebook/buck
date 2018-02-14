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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.ide.intellij.aggregation.DefaultAggregationModuleFactory;
import com.facebook.buck.ide.intellij.lang.android.AndroidManifestParser;
import com.facebook.buck.ide.intellij.lang.java.ParsingJavaPackageFinder;
import com.facebook.buck.ide.intellij.model.IjLibraryFactory;
import com.facebook.buck.ide.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;

/** Top-level class for IntelliJ project generation. */
public class IjProject {

  private final TargetGraphAndTargets targetGraphAndTargets;
  private final JavaPackageFinder javaPackageFinder;
  private final JavaFileParser javaFileParser;
  private final BuildRuleResolver buildRuleResolver;
  private final SourcePathResolver sourcePathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final ProjectFilesystem projectFilesystem;
  private final IjProjectConfig projectConfig;

  public IjProject(
      TargetGraphAndTargets targetGraphAndTargets,
      JavaPackageFinder javaPackageFinder,
      JavaFileParser javaFileParser,
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem projectFilesystem,
      IjProjectConfig projectConfig) {
    this.targetGraphAndTargets = targetGraphAndTargets;
    this.javaPackageFinder = javaPackageFinder;
    this.javaFileParser = javaFileParser;
    this.buildRuleResolver = buildRuleResolver;
    this.ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    this.sourcePathResolver = DefaultSourcePathResolver.from(this.ruleFinder);
    this.projectFilesystem = projectFilesystem;
    this.projectConfig = projectConfig;
  }

  /**
   * Write the project to disk.
   *
   * @return set of {@link BuildTarget}s which should be built in order for the project to index
   *     correctly.
   * @throws IOException
   */
  public ImmutableSet<BuildTarget> write() throws IOException {
    final ImmutableSet.Builder<BuildTarget> requiredBuildTargets = ImmutableSet.builder();
    IjLibraryFactory libraryFactory =
        new DefaultIjLibraryFactory(
            new DefaultIjLibraryFactoryResolver(
                projectFilesystem,
                sourcePathResolver,
                buildRuleResolver,
                ruleFinder,
                requiredBuildTargets));
    IjModuleFactoryResolver moduleFactoryResolver =
        new DefaultIjModuleFactoryResolver(
            buildRuleResolver,
            sourcePathResolver,
            ruleFinder,
            projectFilesystem,
            requiredBuildTargets);
    SupportedTargetTypeRegistry typeRegistry =
        new SupportedTargetTypeRegistry(
            projectFilesystem, moduleFactoryResolver, projectConfig, javaPackageFinder);
    AndroidManifestParser androidManifestParser = new AndroidManifestParser(projectFilesystem);
    IjModuleGraph moduleGraph =
        IjModuleGraphFactory.from(
            projectFilesystem,
            projectConfig,
            targetGraphAndTargets.getTargetGraph(),
            libraryFactory,
            new DefaultIjModuleFactory(projectFilesystem, typeRegistry),
            new DefaultAggregationModuleFactory(typeRegistry));
    JavaPackageFinder parsingJavaPackageFinder =
        ParsingJavaPackageFinder.preparse(
            javaFileParser,
            projectFilesystem,
            IjProjectTemplateDataPreparer.createPackageLookupPathSet(moduleGraph),
            javaPackageFinder);
    IjProjectTemplateDataPreparer templateDataPreparer =
        new IjProjectTemplateDataPreparer(
            parsingJavaPackageFinder,
            moduleGraph,
            projectFilesystem,
            projectConfig,
            androidManifestParser);
    IjProjectWriter writer =
        new IjProjectWriter(templateDataPreparer, projectConfig, projectFilesystem);

    IJProjectCleaner cleaner = new IJProjectCleaner(projectFilesystem);

    writer.write(cleaner);

    PregeneratedCodeWriter pregeneratedCodeWriter =
        new PregeneratedCodeWriter(
            templateDataPreparer, projectConfig, projectFilesystem, androidManifestParser);
    pregeneratedCodeWriter.write(cleaner);

    cleaner.clean(
        projectConfig.getBuckConfig(),
        projectConfig.getProjectPaths().getLibrariesDir(),
        projectConfig.isCleanerEnabled(),
        projectConfig.isRemovingUnusedLibrariesEnabled());

    if (projectConfig.getGeneratedFilesListFilename().isPresent()) {
      cleaner.writeFilesToKeepToFile(projectConfig.getGeneratedFilesListFilename().get());
    }

    return requiredBuildTargets.build();
  }
}
