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

package com.facebook.buck.jvm.java;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import javax.tools.JavaCompiler;

public class ErrorProneJavac extends Jsr199Javac {
  private final static String ERROR_PRONE_VERSION = "2.18";

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return ruleFinder.filterBuildRuleInputs(getInputs());
  }
  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return ImmutableList.of();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("javac", "error-prone")
        .setReflectively("javac.version", "in-memory")
        .setReflectively("errorprone.version", ERROR_PRONE_VERSION)
        .setReflectively("javac.classname", "com.google.errorprone.ErrorProneJavaCompiler");
  }

  @Override
  protected JavaCompiler createCompiler(JavacExecutionContext context) {
    return new com.google.errorprone.ErrorProneJavaCompiler();
  }
}
