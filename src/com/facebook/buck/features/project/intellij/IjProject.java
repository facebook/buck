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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.impl.TargetGraphAndTargets;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.features.project.intellij.aggregation.DefaultAggregationModuleFactory;
import com.facebook.buck.features.project.intellij.lang.android.AndroidManifestParser;
import com.facebook.buck.features.project.intellij.lang.java.ParsingJavaPackageFinder;
import com.facebook.buck.features.project.intellij.model.IjLibraryFactory;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Set;

/** Top-level class for IntelliJ project generation. */
public class IjProject {

  private final TargetGraphAndTargets targetGraphAndTargets;
  private final JavaPackageFinder javaPackageFinder;
  private final JavaFileParser javaFileParser;
  private final ActionGraphBuilder graphBuilder;
  private final SourcePathResolver sourcePathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final ProjectFilesystem projectFilesystem;
  private final IjProjectConfig projectConfig;
  private final ProjectFilesystem outFilesystem;
  private final IJProjectCleaner cleaner;

  public IjProject(
      TargetGraphAndTargets targetGraphAndTargets,
      JavaPackageFinder javaPackageFinder,
      JavaFileParser javaFileParser,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      IjProjectConfig projectConfig,
      ProjectFilesystem outFilesystem) {
    this.targetGraphAndTargets = targetGraphAndTargets;
    this.javaPackageFinder = javaPackageFinder;
    this.javaFileParser = javaFileParser;
    this.graphBuilder = graphBuilder;
    this.ruleFinder = new SourcePathRuleFinder(graphBuilder);
    this.sourcePathResolver = DefaultSourcePathResolver.from(this.ruleFinder);
    this.projectFilesystem = projectFilesystem;
    this.projectConfig = projectConfig;
    this.outFilesystem = outFilesystem;
    cleaner = new IJProjectCleaner(outFilesystem);
  }

  /**
   * Write the project to disk.
   *
   * @return set of {@link BuildTarget}s which should be built in order for the project to index
   *     correctly.
   * @throws IOException
   */
  public ImmutableSet<BuildTarget> write() throws IOException {
    final ImmutableSet<BuildTarget> buildTargets = performWriteOrUpdate(false);
    clean();
    return buildTargets;
  }

  /**
   * Write a subset of the project to disk, specified by the targets passed on the command line.
   * Does not perform a clean of the project space after updating.
   *
   * @return set of {@link BuildTarget}s which should be built in to allow indexing
   * @throws IOException
   */
  public ImmutableSet<BuildTarget> update() throws IOException {
    return performWriteOrUpdate(true);
  }

  /**
   * Perform the write to disk.
   *
   * @param updateOnly whether to write all modules in the graph to disk, or only the ones which
   *     correspond to the listed targets
   * @return set of {@link BuildTarget}s which should be built in order for the project to index
   *     correctly.
   * @throws IOException
   */
  private ImmutableSet<BuildTarget> performWriteOrUpdate(boolean updateOnly) throws IOException {
    final Set<BuildTarget> requiredBuildTargets = Sets.newConcurrentHashSet();
    IjLibraryFactory libraryFactory =
        new DefaultIjLibraryFactory(
            new DefaultIjLibraryFactoryResolver(
                projectFilesystem,
                sourcePathResolver,
                graphBuilder,
                ruleFinder,
                requiredBuildTargets));
    IjModuleFactoryResolver moduleFactoryResolver =
        new DefaultIjModuleFactoryResolver(
            graphBuilder, sourcePathResolver, ruleFinder, projectFilesystem, requiredBuildTargets);
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
    IntellijModulesListParser modulesParser = new IntellijModulesListParser();
    IjProjectWriter writer =
        new IjProjectWriter(
            targetGraphAndTargets.getTargetGraph(),
            templateDataPreparer,
            projectConfig,
            projectFilesystem,
            modulesParser,
            cleaner,
            outFilesystem);

    if (updateOnly) {
      writer.update();
    } else {
      writer.write();
    }
    PregeneratedCodeWriter pregeneratedCodeWriter =
        new PregeneratedCodeWriter(
            templateDataPreparer, projectConfig, outFilesystem, androidManifestParser, cleaner);
    pregeneratedCodeWriter.write();

    if (projectConfig.getGeneratedFilesListFilename().isPresent()) {
      cleaner.writeFilesToKeepToFile(projectConfig.getGeneratedFilesListFilename().get());
    }

    return ImmutableSet.copyOf(requiredBuildTargets);
  }

  /**
   * Run the cleaner after a successful call to write(). This removes stale project files from
   * previous runs.
   */
  private void clean() {
    cleaner.clean(
        projectConfig.getBuckConfig(),
        projectConfig.getProjectPaths().getIdeaConfigDir(),
        projectConfig.getProjectPaths().getLibrariesDir(),
        projectConfig.isCleanerEnabled(),
        projectConfig.isRemovingUnusedLibrariesEnabled());
  }
}
