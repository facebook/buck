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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_OPTIONS;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class JavaBinaryRuleBuilder
    extends AbstractNodeBuilder<
        JavaBinaryDescriptionArg.Builder, JavaBinaryDescriptionArg, JavaBinaryDescription,
        JavaBinary> {

  public JavaBinaryRuleBuilder(BuildTarget target) {
    super(
        new JavaBinaryDescription(
            DEFAULT_JAVA_OPTIONS,
            DEFAULT_JAVAC_OPTIONS,
            CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build())),
            JavaBuckConfig.of(FakeBuckConfig.builder().build())),
        target);
  }

  public JavaBinaryRuleBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public JavaBinaryRuleBuilder setMainClass(String mainClass) {
    getArgForPopulating().setMainClass(Optional.of(mainClass));
    return this;
  }

  public JavaBinaryRuleBuilder addTest(BuildTarget test) {
    getArgForPopulating().addTests(test);
    return this;
  }
}
