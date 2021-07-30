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

package com.facebook.buck.apple;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.cxx.CxxDebugSymbolLinkStrategy;
import com.facebook.buck.cxx.CxxDebugSymbolLinkStrategyFactory;
import com.facebook.buck.cxx.CxxDebugSymbolLinkStrategyFactoryAlwaysDebug;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;

/** Factory to create {@link AppleCxxDebugSymbolLinkStrategy}. */
public class AppleCxxDebugSymbolLinkStrategyFactory implements CxxDebugSymbolLinkStrategyFactory {

  /**
   * Acquires the debug strategy factory. The debug strategies are passed to CxxLink build rules to
   * allow for Apple specific behaviors.
   *
   * @param cxxBuckConfig configs under the "cxx" section
   * @return a debug strategy applicable to CxxLink build rules
   */
  public static CxxDebugSymbolLinkStrategyFactory getDebugStrategyFactory(
      CxxBuckConfig cxxBuckConfig) {
    if (cxxBuckConfig.getFocusedDebuggingEnabled()) {
      // In this case we'll be reading the focused targets from build rule outputs and not
      // from an arbitrary file.
      return new AppleCxxDebugSymbolLinkStrategyFactory();
    } else {
      return CxxDebugSymbolLinkStrategyFactoryAlwaysDebug.FACTORY;
    }
  }

  @Override
  public CxxDebugSymbolLinkStrategy createStrategy(
      CellPathResolver cellPathResolver, ImmutableList<Arg> linkerArgs) {
    return new AppleCxxDebugSymbolLinkStrategy();
  }
}
