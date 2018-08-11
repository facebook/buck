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

package com.facebook.buck.apple;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import org.pf4j.Extension;

@Extension
public class AppleXCodeDescriptionClassSupplier implements XCodeDescriptionClassSupplier {

  @Override
  public Collection<Class<? extends BaseDescription<?>>> getXCodeDescriptions() {
    return ImmutableSet.of(
        AppleLibraryDescription.class,
        CxxLibraryDescription.class,
        AppleBinaryDescription.class,
        AppleBundleDescription.class,
        AppleTestDescription.class,
        SwiftLibraryDescription.class);
  }
}
