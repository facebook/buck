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

import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.DefaultJavaLibraryBuilder;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;

class DefaultGroovyLibraryBuilder extends DefaultJavaLibraryBuilder {
  protected DefaultGroovyLibraryBuilder(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CompileToJarStepFactory compileStepFactory) {
    super(params, buildRuleResolver, compileStepFactory);
  }

  public DefaultGroovyLibraryBuilder setConfigAndArgs(
      GroovyBuckConfig config,
      GroovyLibraryDescription.Arg args) {
    setSuggestDependencies(config.shouldSuggestDependencies());
    setArgs(args);
    return this;
  }
}
