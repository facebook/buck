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

package com.facebook.buck.core.starlark.rule.data;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.skylarkinterface.StarlarkContext;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkIndexable;

/**
 * Skylark object provided to users to get extra information about a dependency, including its
 * original build target, its {@link ProviderInfoCollection}, and more in the future. {@link
 * SkylarkIndexable} operations are proxied to the provided {@link ProviderInfoCollection}
 */
public class SkylarkDependency implements SkylarkValue, SkylarkIndexable {

  private final Label label;
  private final ProviderInfoCollection providerInfos;

  public SkylarkDependency(BuildTarget target, ProviderInfoCollection providerInfos) {
    try {
      // TODO(T47757916): We may make BuildTarget just work properly in skylark in the future
      this.label = Label.parseAbsolute(target.getFullyQualifiedName(), ImmutableMap.of());
    } catch (LabelSyntaxException e) {
      throw new IllegalStateException(
          String.format(
              "Expected %s could not be parsed as a Label: %s",
              target.getFullyQualifiedName(), e.getMessage()),
          e);
    }
    this.providerInfos = providerInfos;
  }

  @SkylarkCallable(name = "label", structField = true)
  public Label label() {
    return label;
  }

  public ProviderInfoCollection getProviderInfos() {
    return providerInfos;
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.format("<dependency %s>", label);
  }

  @Override
  public Object getIndex(Object key, Location loc, StarlarkContext context) throws EvalException {
    return providerInfos.getIndex(key, loc, context);
  }

  @Override
  public boolean containsKey(Object key, Location loc, StarlarkContext context)
      throws EvalException {
    return providerInfos.containsKey(key, loc, context);
  }
}
