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

import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.starlark.compatible.StarlarkExportable;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Location;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkCallable;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.StarlarkValue;
import java.util.LinkedHashMap;
import javax.annotation.Nullable;

/**
 * A {@link Provider} defined by a user in a build file that creates {@link UserDefinedProviderInfo}
 * instances. This is only intended to be used within the context of user defined rules, and as
 * such, {@link UserDefinedProviderInfo} instances that are created contain skylark-compatible
 * values, rather than normal java/guava classes. In the future type restriction may also be
 * allowed.
 *
 * <p>NOTE: Until {@link #export(com.facebook.buck.core.model.label.Label, String)} is called, many
 * methods (especially ones that get the user defined name of the class) are not safe to call.
 */
public class UserDefinedProvider
    implements StarlarkCallable,
        Provider<UserDefinedProviderInfo>,
        StarlarkValue,
        StarlarkExportable {

  private final Key key;
  private final Location location;
  private boolean isExported = false;
  @Nullable private String name = null;

  private final ImmutableSet<String> fieldNames;

  /**
   * Create an instance of {@link UserDefinedProvider}
   *
   * @param location The location where the provider was defined by the user
   * @param fieldNames List of kwargs that will be available when {@link #fastcall(StarlarkThread,
   *     Object[], Object[])} is called, and will be available as fields on the resulting {@link
   *     UserDefinedProviderInfo} object
   */
  public UserDefinedProvider(Location location, String[] fieldNames) {
    this.location = location;
    this.key = new Key();
    this.fieldNames = ImmutableSet.copyOf(fieldNames);
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
  public void repr(Printer printer) {
    printer.format("%s(", getName());
    MoreIterables.enumerate(fieldNames)
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
  public Location getLocation() {
    return location;
  }

  @Override
  public void export(Label extensionLabel, String exportedName) throws EvalException {
    Preconditions.checkState(!isExported);
    name = exportedName;
    isExported = true;
  }

  @Override
  public String toString() {
    return getName();
  }

  @Override
  public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {
    Verify.verify(isExported, "Tried to call a Provider before exporting it");

    if (positional.length != 0) {
      throw Starlark.errorf("providers only accept named arguments");
    }

    LinkedHashMap<String, Object> fieldValues = new LinkedHashMap<>();
    for (int i = 0; i < named.length; i += 2) {
      String name = (String) named[i];
      Object value = named[i + 1];

      if (!fieldNames.contains(name)) {
        throw Starlark.errorf("unknown argument: %s", name);
      }

      Object prevValue = fieldValues.putIfAbsent(name, value);
      if (prevValue != null) {
        throw Starlark.errorf("duplicate argument: %s", name);
      }
    }

    for (String fieldName : fieldNames) {
      fieldValues.putIfAbsent(fieldName, Starlark.NONE);
    }

    return new UserDefinedProviderInfo(this, ImmutableMap.copyOf(fieldValues));
  }

  private class Key implements Provider.Key<UserDefinedProviderInfo> {}
}
