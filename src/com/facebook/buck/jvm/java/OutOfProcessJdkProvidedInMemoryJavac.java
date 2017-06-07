/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class OutOfProcessJdkProvidedInMemoryJavac extends OutOfProcessJsr199Javac {
  private static final Logger LOG = Logger.get(OutOfProcessJdkProvidedInMemoryJavac.class);

  OutOfProcessJdkProvidedInMemoryJavac() {}

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("javac", "oop-jsr199").setReflectively("javac.type", "oop-in-memory");
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return ImmutableSortedSet.of();
  }

  @Override
  public int buildWithClasspath(
      JavacExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableList<JavacPluginJsr199Fields> pluginFields,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      JavacCompilationMode compilationMode)
      throws InterruptedException {
    Map<String, Object> serializedContext = JavacExecutionContextSerializer.serialize(context);
    if (LOG.isVerboseEnabled()) {
      LOG.verbose("Serialized JavacExecutionContext: %s", serializedContext);
    }

    return getConnection()
        .getRemoteObjectProxy()
        .buildWithClasspath(
            null,
            serializedContext,
            invokingRule.getFullyQualifiedName(),
            options,
            javaSourceFilePaths.stream().map(Path::toString).collect(Collectors.toList()),
            pathToSrcsList.toString(),
            workingDirectory.isPresent() ? workingDirectory.get().toString() : null,
            pluginFields,
            compilationMode.toString());
  }
}
