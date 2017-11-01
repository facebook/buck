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

import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
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
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class OutOfProcessJarBackedJavac extends OutOfProcessJsr199Javac {

  private static final Logger LOG = Logger.get(OutOfProcessJarBackedJavac.class);

  private final String compilerClassName;
  private final ImmutableSortedSet<SourcePath> classpath;

  public OutOfProcessJarBackedJavac(String compilerClassName, Iterable<SourcePath> classpath) {
    this.compilerClassName = compilerClassName;
    this.classpath = ImmutableSortedSet.copyOf(classpath);
  }

  @Override
  public Javac.Invocation newBuildInvocation(
      JavacExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableList<JavacPluginJsr199Fields> pluginFields,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      Path workingDirectory,
      boolean trackClassUsage,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      AbiGenerationMode abiGenerationMode,
      @Nullable SourceOnlyAbiRuleInfo ruleInfo) {

    Map<String, Object> serializedContext = JavacExecutionContextSerializer.serialize(context);
    if (LOG.isVerboseEnabled()) {
      LOG.verbose("Serialized JavacExecutionContext: %s", serializedContext);
    }

    OutOfProcessJavacConnectionInterface proxy = getConnection().getRemoteObjectProxy();
    return wrapInvocation(
        proxy,
        proxy.newBuildInvocation(
            compilerClassName,
            serializedContext,
            invokingRule.getFullyQualifiedName(),
            options,
            javaSourceFilePaths.stream().map(Path::toString).collect(Collectors.toList()),
            pathToSrcsList.toString(),
            workingDirectory.toString(),
            trackClassUsage,
            abiJarParameters != null ? JarParametersSerializer.serialize(abiJarParameters) : null,
            libraryJarParameters != null
                ? JarParametersSerializer.serialize(libraryJarParameters)
                : null,
            pluginFields
                .stream()
                .map(JavacPluginJsr199FieldsSerializer::serialize)
                .collect(Collectors.toList()),
            abiGenerationMode.toString()));
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return ruleFinder.filterBuildRuleInputs(getInputs());
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return classpath;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("javac", "oop-jar-backed-jsr199")
        .setReflectively("javac.type", "oop-in-memory")
        .setReflectively("javac.classname", compilerClassName)
        .setReflectively("javac.classpath", classpath);
  }
}
