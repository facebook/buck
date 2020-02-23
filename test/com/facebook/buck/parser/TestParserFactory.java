/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import org.pf4j.PluginManager;

public class TestParserFactory {
  public static Parser create(DepsAwareExecutor<? super ComputeResult, ?> executor, Cell cell) {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(pluginManager);
    return create(executor, cell, knownRuleTypesProvider);
  }

  public static Parser create(
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      Cell cell,
      KnownRuleTypesProvider knownRuleTypesProvider,
      BuckEventBus eventBus) {
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ParserConfig parserConfig = cell.getBuckConfig().getView(ParserConfig.class);
    return create(
        executor,
        cell,
        new PerBuildStateFactory(
            typeCoercerFactory,
            new DefaultConstructorArgMarshaller(),
            knownRuleTypesProvider,
            new ParserPythonInterpreterProvider(parserConfig, new ExecutableFinder()),
            WatchmanFactory.NULL_WATCHMAN,
            eventBus,
            new ParsingUnconfiguredBuildTargetViewFactory(),
            UnconfiguredTargetConfiguration.INSTANCE),
        eventBus);
  }

  public static Parser create(
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      Cell cell,
      KnownRuleTypesProvider knownRuleTypesProvider) {
    return create(executor, cell, knownRuleTypesProvider, BuckEventBusForTests.newInstance());
  }

  public static Parser create(
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      Cell cell,
      PerBuildStateFactory perBuildStateFactory) {
    return create(executor, cell, perBuildStateFactory, BuckEventBusForTests.newInstance());
  }

  public static Parser create(
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      Cell cell,
      PerBuildStateFactory perBuildStateFactory,
      BuckEventBus eventBus) {
    ParserConfig parserConfig = cell.getBuckConfig().getView(ParserConfig.class);

    return new ParserWithConfigurableAttributes(
        new DaemonicParserState(parserConfig.getNumParsingThreads()),
        perBuildStateFactory,
        TestTargetSpecResolverFactory.create(executor, cell.getCellProvider(), eventBus),
        eventBus,
        BuildBuckConfig.of(cell.getBuckConfig()).shouldBuckOutIncludeTargetConfigHash());
  }
}
