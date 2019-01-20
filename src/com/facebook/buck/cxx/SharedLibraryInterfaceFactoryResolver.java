/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.cxx.toolchain.ElfSharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceFactory;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceParams;

class SharedLibraryInterfaceFactoryResolver {

  public static SharedLibraryInterfaceFactory resolveFactory(SharedLibraryInterfaceParams params) {
    switch (params.getKind()) {
      case ELF:
        ElfSharedLibraryInterfaceParams elfParams = (ElfSharedLibraryInterfaceParams) params;
        return ElfSharedLibraryInterfaceFactory.from(elfParams);
      default:
        throw new HumanReadableException("Unknown shared library type: " + params.getKind());
    }
  }
}
