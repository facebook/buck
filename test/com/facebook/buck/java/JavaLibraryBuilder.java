/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.List;

public class JavaLibraryBuilder
    extends AbstractBuilder<DefaultJavaLibrary, JavaLibraryDescription.Arg> {

  protected JavaLibraryBuilder(BuildTarget target) {
    super(new JavaLibraryDescription(JavaCompilerEnvironment.DEFAULT), target);
  }

  public static JavaLibraryBuilder createBuilder(BuildTarget target) {
    return new JavaLibraryBuilder(target);
  }

  public JavaLibraryBuilder addAllAnnotationProcessors(List<String> processorNames) {
    arg.annotationProcessors = Optional.of(ImmutableSet.copyOf(processorNames));
    return this;
  }

  public JavaLibraryBuilder addDep(BuildRule rule) {
    arg.deps = amend(arg.deps, rule);
    return this;
  }

  public JavaLibraryBuilder addExportedDep(BuildRule rule) {
    arg.exportedDeps = amend(arg.exportedDeps, rule);
    return this;
  }

  public JavaLibraryBuilder addResource(SourcePath sourcePath) {
    arg.resources = amend(arg.resources, sourcePath);
    return this;
  }

  public JavaLibraryBuilder addSrc(Path path) {
    arg.srcs = amend(arg.srcs, new PathSourcePath(path));
    return this;
  }
}
