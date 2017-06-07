/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.SourcePath;
import java.util.Optional;

// Immutables get really angry if you try to inherit from other Immutables. So we need to extract
// the core ReactNativeLibrary stuff here, which we can then extend in each ReactNative Arg.
public interface CoreReactNativeLibraryArg extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs {
  SourcePath getEntryPath();

  String getBundleName();

  Optional<String> getPackagerFlags();
}
