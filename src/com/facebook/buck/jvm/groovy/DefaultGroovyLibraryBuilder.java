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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;

final class DefaultGroovyLibraryBuilder {
  private DefaultGroovyLibraryBuilder() {}

  public static DefaultJavaLibraryRules newInstance(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      JavacOptions javacOptions,
      GroovyBuckConfig groovyBuckConfig,
      GroovyLibraryDescription.CoreArg args) {
    return new DefaultJavaLibraryRules.Builder(
            buildTarget,
            projectFilesystem,
            params,
            buildRuleResolver,
            new GroovyConfiguredCompilerFactory(groovyBuckConfig),
            null,
            args)
        .setJavacOptions(javacOptions)
        .build();
  }
}
