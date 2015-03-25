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

import static com.facebook.buck.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;

import java.nio.file.Path;

public class JavaLibraryBuilder extends AbstractNodeBuilder<JavaLibraryDescription.Arg> {

  protected JavaLibraryBuilder(BuildTarget target) {
    super(new JavaLibraryDescription(DEFAULT_JAVAC_OPTIONS), target);
  }

  public static JavaLibraryBuilder createBuilder(BuildTarget target) {
    return new JavaLibraryBuilder(target);
  }

  public JavaLibraryBuilder addDep(BuildTarget rule) {
    arg.deps = amend(arg.deps, rule);
    return this;
  }

  public JavaLibraryBuilder addExportedDep(BuildTarget rule) {
    arg.exportedDeps = amend(arg.exportedDeps, rule);
    return this;
  }

  public JavaLibraryBuilder addResource(SourcePath sourcePath) {
    arg.resources = amend(arg.resources, sourcePath);
    return this;
  }

  public JavaLibraryBuilder addSrc(Path path) {
    arg.srcs = amend(arg.srcs, new PathSourcePath(new FakeProjectFilesystem(), path));
    return this;
  }

  public JavaLibraryBuilder setProguardConfig(Path proguardConfig) {
    arg.proguardConfig = Optional.of(proguardConfig);
    return this;
  }

  public JavaLibraryBuilder setCompiler(BuildRule javac) {
    Either<BuildTarget, Path> right = Either.ofLeft(javac.getBuildTarget());
    Either<BuiltInJavac, Either<BuildTarget, Path>> value = Either.ofRight(right);

    arg.compiler = Optional.of(value);
    return this;
  }
}
