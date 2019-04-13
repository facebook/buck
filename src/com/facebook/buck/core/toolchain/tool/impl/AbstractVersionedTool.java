/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.core.toolchain.tool.impl;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rules.modern.HasCustomInputsLogic;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.rules.modern.annotations.DefaultFieldSerialization;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.function.ThrowingConsumer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import org.immutables.value.Value;

/**
 * A {@link Tool} which only contributes a fixed name and version when appended to a rule key. This
 * class also holds a {@link Path} reference to the tool and additional flags used when invoking the
 * tool, which do *not* themselves contribute to the rule key. This is useful in situations such as
 * supporting cross-compilation, in which case the different tools themselves might not be
 * bit-by-bit identical (and, similarly, they might need to invoked with slightly different flags)
 * but we know that they produce identical output, in which case they should also generate identical
 * rule keys.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractVersionedTool implements Tool, HasCustomInputsLogic {

  /** The path to the tool. The contents or path to the tool do not contribute to the rule key. */
  @Value.Parameter
  @CustomFieldBehavior(DefaultFieldSerialization.class)
  @ExcludeFromRuleKey(
      "We only add the version and name to the rulekey, this depends on the creator of the versioned tool to do the right thing.")
  protected abstract PathSourcePath getPath();

  /** Additional flags that we pass to the tool, but which do *not* contribute to the rule key. */
  @CustomFieldBehavior(DefaultFieldSerialization.class)
  @ExcludeFromRuleKey(
      "We only add the version and name to the rulekey, this depends on the creator of the versioned tool to do the right thing.")
  protected abstract ImmutableList<String> getExtraArgs();

  @Value.Parameter
  @AddToRuleKey
  protected abstract String getName();

  @Value.Parameter
  @AddToRuleKey
  protected abstract String getVersion();

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return ImmutableList.<String>builder().add(getPath().toString()).addAll(getExtraArgs()).build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return ImmutableMap.of();
  }

  @Override
  public <E extends Exception> void computeInputs(ThrowingConsumer<SourcePath, E> consumer)
      throws E {
    consumer.accept(getPath());
  }
}
