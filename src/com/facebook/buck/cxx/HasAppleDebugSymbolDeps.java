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
package com.facebook.buck.cxx;

import com.facebook.buck.core.rules.BuildRule;
import java.util.stream.Stream;

/**
 * Returns all archives and object files, or other instances of HasAppleDebugSymbolDeps.
 *
 * <p>On Apple platforms, debug symbols are not linked into the main binary at link time. Instead,
 * the linked binary points to all the object and archives where debug information can be found.
 * This information can be collected (via the {@link com.facebook.buck.apple.AppleDsym} step) into a
 * file, or used by debuggers directly.
 */
public interface HasAppleDebugSymbolDeps extends BuildRule {
  Stream<BuildRule> getAppleDebugSymbolDeps();
}
