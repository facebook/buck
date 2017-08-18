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

package com.facebook.buck.cxx.toolchain.linker;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.ToolProvider;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import javax.annotation.Nonnull;

public class DefaultLinkerProvider implements LinkerProvider {

  private final Type type;
  private final ToolProvider toolProvider;

  private final LoadingCache<BuildRuleResolver, Linker> cache =
      CacheBuilder.newBuilder()
          .weakKeys()
          .build(
              new CacheLoader<BuildRuleResolver, Linker>() {
                @Override
                public Linker load(@Nonnull BuildRuleResolver resolver) {
                  return build(type, toolProvider.resolve(resolver));
                }
              });

  public DefaultLinkerProvider(Type type, ToolProvider toolProvider) {
    this.type = type;
    this.toolProvider = toolProvider;
  }

  private static Linker build(Type type, Tool tool) {
    switch (type) {
      case DARWIN:
        return new DarwinLinker(tool);
      case GNU:
        return new GnuLinker(tool);
      case WINDOWS:
        return new WindowsLinker(tool);
      case UNKNOWN:
      default:
        throw new IllegalStateException("unexpected type: " + type);
    }
  }

  @Override
  public synchronized Linker resolve(BuildRuleResolver resolver) {
    return cache.getUnchecked(resolver);
  }

  @Override
  public Type getType() {
    return type;
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps() {
    return toolProvider.getParseTimeDeps();
  }
}
