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

package com.facebook.buck.java.intellij;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraphAndTargets;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Top-level class for IntelliJ project generation.
 */
public class IjProject {

  private final TargetGraphAndTargets targetGraphAndTargets;
  private final JavaPackageFinder javaPackageFinder;
  private final SourcePathResolver resolver;
  private final ProjectFilesystem projectFilesystem;

  public IjProject(
      TargetGraphAndTargets targetGraphAndTargets,
      JavaPackageFinder javaPackageFinder,
      SourcePathResolver resolver,
      ProjectFilesystem projectFilesystem) {
    this.targetGraphAndTargets = targetGraphAndTargets;
    this.javaPackageFinder = javaPackageFinder;
    this.resolver = resolver;
    this.projectFilesystem = projectFilesystem;
  }

  public void write() throws IOException {
    IjLibraryFactory.IjLibraryFactoryResolver libraryFactoryResolver =
        new IjLibraryFactory.IjLibraryFactoryResolver() {
          @Override
          public Path getPath(SourcePath path) {
            return resolver.getPath(path);
          }
        };
    IjModuleGraph moduleGraph = IjModuleGraph.from(
        targetGraphAndTargets.getTargetGraph(),
        libraryFactoryResolver);
    IjProjectWriter writer = new IjProjectWriter(
        javaPackageFinder,
        moduleGraph,
        projectFilesystem);
    writer.write();
  }
}
