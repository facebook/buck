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

package com.facebook.buck.apple.toolchain;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import java.util.function.Supplier;

/** A collection of code sign identities. */
public class CodeSignIdentityStore implements AddsToRuleKey {

  @AddToRuleKey private final Supplier<ImmutableList<CodeSignIdentity>> identitiesSupplier;

  public CodeSignIdentityStore(Supplier<ImmutableList<CodeSignIdentity>> identitiesSupplier) {
    this.identitiesSupplier = identitiesSupplier;
  }

  /** Get all the identities in the store. */
  public ImmutableList<CodeSignIdentity> getIdentities() {
    return identitiesSupplier.get();
  }

  public static CodeSignIdentityStore fromIdentities(Iterable<CodeSignIdentity> identities) {
    return new CodeSignIdentityStore(Suppliers.ofInstance(ImmutableList.copyOf(identities)));
  }
}
