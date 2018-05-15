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

package com.facebook.buck.jvm.java.autodeps;

import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.build.engine.BuildEngine;
import com.facebook.buck.core.build.engine.BuildEngineBuildContext;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.type.BuildRuleType;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.PrebuiltJarDescription;
import com.facebook.buck.jvm.java.PrebuiltJarDescriptionArg;
import com.facebook.buck.rules.DescriptionCache;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Set;

public class JavaDepsFinder {

  private final JavaFileParser javaFileParser;
  private final BuildEngineBuildContext buildContext;
  private final ExecutionContext executionContext;
  private final BuildEngine buildEngine;

  public JavaDepsFinder(
      JavaFileParser javaFileParser,
      BuildEngineBuildContext buildContext,
      ExecutionContext executionContext,
      BuildEngine buildEngine) {
    this.javaFileParser = javaFileParser;
    this.buildContext = buildContext;
    this.executionContext = executionContext;
    this.buildEngine = buildEngine;
  }

  public JavaFileParser getJavaFileParser() {
    return javaFileParser;
  }

  public static JavaDepsFinder createJavaDepsFinder(
      BuckConfig buckConfig,
      BuildEngineBuildContext buildContext,
      ExecutionContext executionContext,
      BuildEngine buildEngine) {
    JavaBuckConfig javaBuckConfig = buckConfig.getView(JavaBuckConfig.class);
    JavacOptions javacOptions = javaBuckConfig.getDefaultJavacOptions();
    JavaFileParser javaFileParser = JavaFileParser.createJavaFileParser(javacOptions);

    return new JavaDepsFinder(javaFileParser, buildContext, executionContext, buildEngine);
  }

  private static final Set<BuildRuleType> RULES_TO_VISIT =
      ImmutableSet.of(
          DescriptionCache.getBuildRuleType(AndroidLibraryDescription.class),
          DescriptionCache.getBuildRuleType(JavaLibraryDescription.class),
          DescriptionCache.getBuildRuleType(JavaTestDescription.class),
          DescriptionCache.getBuildRuleType(PrebuiltJarDescription.class));

  /** Java dependency information that is extracted from a {@link TargetGraph}. */
  public static class DependencyInfo {
    public final HashMultimap<String, TargetNode<?, ?>> symbolToProviders = HashMultimap.create();
  }

  public DependencyInfo findDependencyInfoForGraph(TargetGraph graph) {
    DependencyInfo dependencyInfo = new DependencyInfo();

    // Walk the graph and for each Java rule we record the Java entities it provides.
    //
    // Currently, we traverse the entire target graph using a single thread. However, the work to
    // visit each node could be done in parallel, so long as the updates to the above collections
    // were thread-safe.
    for (TargetNode<?, ?> node : graph.getNodes()) {
      if (!RULES_TO_VISIT.contains(node.getBuildRuleType())) {
        continue;
      }

      if (!(node.getConstructorArg() instanceof JavaLibraryDescription.CoreArg)
          && !(node.getConstructorArg() instanceof PrebuiltJarDescriptionArg)) {
        throw new IllegalStateException("This rule is not supported by suggest: " + node);
      }

      Symbols symbols = getJavaFileFeatures(node);
      for (String providedEntity : symbols.provided) {
        dependencyInfo.symbolToProviders.put(providedEntity, node);
      }
    }

    return dependencyInfo;
  }

  private Symbols getJavaFileFeatures(TargetNode<?, ?> node) {
    // Build a JavaLibrarySymbolsFinder to create the JavaFileFeatures. By making use of Buck's
    // build cache, we can often avoid running a Java parser.
    BuildTarget buildTarget = node.getBuildTarget();
    Object argForNode = node.getConstructorArg();
    JavaSymbolsRule.SymbolsFinder symbolsFinder;
    if (argForNode instanceof JavaLibraryDescription.CoreArg) {
      JavaLibraryDescription.CoreArg arg = (JavaLibraryDescription.CoreArg) argForNode;
      symbolsFinder = new JavaLibrarySymbolsFinder(arg.getSrcs(), javaFileParser);
    } else {
      PrebuiltJarDescriptionArg arg = (PrebuiltJarDescriptionArg) argForNode;
      symbolsFinder = new PrebuiltJarSymbolsFinder(arg.getBinaryJar());
    }

    // Build the rule, leveraging Buck's build cache.
    JavaSymbolsRule buildRule =
        new JavaSymbolsRule(buildTarget, symbolsFinder, node.getFilesystem());
    ListenableFuture<BuildResult> future =
        buildEngine.build(buildContext, executionContext, buildRule).getResult();
    BuildResult result = Futures.getUnchecked(future);

    Symbols features;
    if (result.isSuccess()) {
      features = buildRule.getFeatures();
    } else {
      Throwable failure = result.getFailure();
      Preconditions.checkNotNull(failure);
      throw new RuntimeException("Failed to extract Java symbols for " + buildTarget, failure);
    }
    return features;
  }
}
