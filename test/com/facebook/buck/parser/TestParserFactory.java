/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.parser;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import org.pf4j.PluginManager;

public class TestParserFactory {
  public static Parser create(BuckConfig buckConfig) {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(pluginManager);
    return create(buckConfig, knownRuleTypesProvider);
  }

  private static ThrowingCloseableMemoizedSupplier<ManifestService, IOException>
      getManifestSupplier() {
    return ThrowingCloseableMemoizedSupplier.of(() -> null, ManifestService::close);
  }

  public static Parser create(
      BuckConfig buckConfig, KnownRuleTypesProvider knownRuleTypesProvider, BuckEventBus eventBus) {
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
    return create(
        buckConfig,
        PerBuildStateFactory.createFactory(
            typeCoercerFactory,
            new DefaultConstructorArgMarshaller(typeCoercerFactory),
            knownRuleTypesProvider,
            new ParserPythonInterpreterProvider(parserConfig, new ExecutableFinder()),
            buckConfig,
            WatchmanFactory.NULL_WATCHMAN,
            eventBus,
            getManifestSupplier(),
            new FakeFileHashCache(ImmutableMap.of()),
            new ParsingUnconfiguredBuildTargetFactory()),
        eventBus);
  }

  public static Parser create(
      BuckConfig buckConfig, KnownRuleTypesProvider knownRuleTypesProvider) {
    return create(buckConfig, knownRuleTypesProvider, BuckEventBusForTests.newInstance());
  }

  public static Parser create(BuckConfig buckConfig, PerBuildStateFactory perBuildStateFactory) {
    return create(buckConfig, perBuildStateFactory, BuckEventBusForTests.newInstance());
  }

  public static Parser create(
      BuckConfig buckConfig, PerBuildStateFactory perBuildStateFactory, BuckEventBus eventBus) {
    ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
    return new DefaultParser(
        new DaemonicParserState(parserConfig.getNumParsingThreads()),
        perBuildStateFactory,
        new TargetSpecResolver(eventBus, WatchmanFactory.NULL_WATCHMAN),
        eventBus,
        ImmutableList::of);
  }
}
