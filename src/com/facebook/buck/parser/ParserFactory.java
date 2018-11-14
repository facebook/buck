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
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.function.Supplier;

/** Responsible for creating an instance of {@link Parser}. */
public class ParserFactory {

  /** Creates an instance of {@link Parser}. */
  public static Parser create(
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider,
      BuckConfig buckConfig,
      DaemonicParserState daemonicParserState,
      TargetSpecResolver targetSpecResolver,
      Watchman watchman,
      BuckEventBus eventBus,
      Supplier<ImmutableList<String>> targetPlatforms,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashCache fileHashCache) {
    if (buckConfig.getView(ParserConfig.class).getEnableConfigurableAttributes()) {
      return new ParserWithConfigurableAttributes(
          daemonicParserState,
          new PerBuildStateFactoryWithConfigurableAttributes(
              typeCoercerFactory,
              marshaller,
              knownRuleTypesProvider,
              parserPythonInterpreterProvider,
              watchman,
              eventBus,
              manifestServiceSupplier,
              fileHashCache),
          targetSpecResolver,
          watchman,
          eventBus,
          targetPlatforms);
    } else {
      return new DefaultParser(
          daemonicParserState,
          new LegacyPerBuildStateFactory(
              typeCoercerFactory,
              marshaller,
              knownRuleTypesProvider,
              parserPythonInterpreterProvider,
              watchman,
              eventBus,
              manifestServiceSupplier,
              fileHashCache),
          targetSpecResolver,
          watchman,
          eventBus,
          targetPlatforms);
    }
  }
}
