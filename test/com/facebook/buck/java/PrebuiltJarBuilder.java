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
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;

import java.nio.file.Path;

public class PrebuiltJarBuilder extends AbstractNodeBuilder<PrebuiltJarDescription.Arg> {

  private PrebuiltJarBuilder(BuildTarget target) {
    super(new PrebuiltJarDescription(), target);
  }

  public static PrebuiltJarBuilder createBuilder(BuildTarget target) {
    return new PrebuiltJarBuilder(target);
  }

  public PrebuiltJarBuilder setBinaryJar(Path binaryJar) {
    arg.binaryJar = new PathSourcePath(new FakeProjectFilesystem(), binaryJar);
    return this;
  }

  public PrebuiltJarBuilder addDep(BuildTarget dep) {
    arg.deps = amend(arg.deps, dep);
    return this;
  }

}
