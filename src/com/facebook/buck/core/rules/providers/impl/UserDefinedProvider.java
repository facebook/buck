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

package com.facebook.buck.core.rules.providers.impl;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.SkylarkExportable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Runtime;
import java.util.Collections;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A {@link Provider} defined by a user in a build file that creates {@link UserDefinedProviderInfo}
 * instances. This is only intended to be used within the context of user defined rules, and as
 * such, {@link UserDefinedProviderInfo} instances that are created contain skylark-compatible
 * values, rather than normal java/guava classes. In the future type restriction may also be
 * allowed.
 *
 * <p>NOTE: Until {@link #export(Label, String)} is called, many methods (especially ones that get
 * the user defined name of the class) are not safe to call.
 */
public class UserDefinedProvider extends BaseFunction
    implements Provider<UserDefinedProviderInfo>, SkylarkValue, SkylarkExportable {

  private final Key key;
  private boolean isExported = false;
  @Nullable private String name = null;

  /**
   * Create an instance of {@link UserDefinedProvider}
   *
   * @param location The location where the provider was defined by the user
   * @param fieldNames List of kwargs that will be available when {@link #call(Object[],
   *     FuncallExpression, Environment)} is called, and will be available as fields on the
   *     resulting {@link UserDefinedProviderInfo} object
   */
  public UserDefinedProvider(Location location, String[] fieldNames) {
    super(
        null,
        FunctionSignature.WithValues.create(
            FunctionSignature.namedOnly(0, fieldNames),
            Collections.nCopies(fieldNames.length, Runtime.NONE),
            null),
        location);
    this.key = new Key();
  }

  @Override
  public boolean isImmutable() {
    return isExported();
  }

  @Override
  public Provider.Key<UserDefinedProviderInfo> getKey() {
    return key;
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.format("%s(", getName());
    MoreIterables.enumerate(Objects.requireNonNull(getSignature()).getSignature().getNames())
        .forEach(
            pair -> {
              if (pair.getFirst() != 0) {
                printer.append(", ");
              }
              printer.append(pair.getSecond());
            });
    printer.append(") defined at ");
    printer.repr(getLocation());
  }

  @Override
  public boolean isExported() {
    return isExported;
  }

  @Override
  public String getName() {
    return Preconditions.checkNotNull(
        name, "Tried to get name before function has been assigned to a variable and exported");
  }

  @Override
  public void export(Label extensionLabel, String exportedName) throws EvalException {
    name = exportedName;
    isExported = true;
  }

  @Override
  public String toString() {
    return getName();
  }

  @Override
  protected Object call(Object[] args, @Nullable FuncallExpression ast, Environment env) {
    Verify.verify(isExported, "Tried to call a Provider before exporting it");

    ImmutableList<String> fieldNames =
        Objects.requireNonNull(getSignature()).getSignature().getNames();
    Verify.verify(args.length == fieldNames.size());

    ImmutableMap.Builder<String, Object> builder =
        ImmutableMap.builderWithExpectedSize(args.length);
    for (int i = 0; i < fieldNames.size(); i++) {
      builder.put(fieldNames.get(i), args[i]);
    }
    return new UserDefinedProviderInfo(this, builder.build());
  }

  private class Key implements Provider.Key<UserDefinedProviderInfo> {}
}
