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

import com.facebook.buck.core.cell.Cells;
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
  public static Parser create(DepsAwareExecutor<? super ComputeResult, ?> executor, Cells cells) {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(pluginManager);
    return create(executor, cells, knownRuleTypesProvider);
  }

  public static Parser create(
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      Cells cells,
      KnownRuleTypesProvider knownRuleTypesProvider,
      BuckEventBus eventBus) {
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ParserConfig parserConfig = cells.getRootCell().getBuckConfig().getView(ParserConfig.class);
    return create(
        executor,
        cells,
        new PerBuildStateFactory(
            typeCoercerFactory,
            new DefaultConstructorArgMarshaller(),
            knownRuleTypesProvider,
            new ParserPythonInterpreterProvider(parserConfig, new ExecutableFinder()),
            new WatchmanFactory.NullWatchman("test"),
            eventBus,
            new ParsingUnconfiguredBuildTargetViewFactory(),
            UnconfiguredTargetConfiguration.INSTANCE),
        eventBus);
  }

  public static Parser create(
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      Cells cells,
      KnownRuleTypesProvider knownRuleTypesProvider) {
    return create(executor, cells, knownRuleTypesProvider, BuckEventBusForTests.newInstance());
  }

  public static Parser create(
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      Cells cells,
      PerBuildStateFactory perBuildStateFactory) {
    return create(executor, cells, perBuildStateFactory, BuckEventBusForTests.newInstance());
  }

  public static Parser create(
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      Cells cell,
      PerBuildStateFactory perBuildStateFactory,
      BuckEventBus eventBus) {

    return new ParserWithConfigurableAttributes(
        new DaemonicParserState(),
        perBuildStateFactory,
        TestTargetSpecResolverFactory.create(executor, cell.getCellProvider(), eventBus),
        eventBus);
  }
}
