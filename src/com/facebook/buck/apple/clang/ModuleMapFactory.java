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

package com.facebook.buck.apple.clang;

/**
 * Creates module map instances.
 *
 * <p>Use this instead of directly creating UmbrellaHeaderModuleMap or UmbrellaDirectoryModuleMap
 * instances.
 */
public class ModuleMapFactory {

  /**
   * Creates a module map.
   *
   * @param moduleName The name of the module.
   * @param moduleMapMode The module map mode to use.
   * @param swiftMode The Swift mode to use for umbrella header module maps. This parameter is
   *     unused with umbrella directory module maps.
   * @return A module map instance.
   */
  public static ModuleMap createModuleMap(
      String moduleName, ModuleMapMode moduleMapMode, UmbrellaHeaderModuleMap.SwiftMode swiftMode) {
    switch (moduleMapMode) {
      case UMBRELLA_HEADER:
        return new UmbrellaHeaderModuleMap(moduleName, swiftMode);
      case UMBRELLA_DIRECTORY:
        return new UmbrellaDirectoryModuleMap(moduleName);
    }

    throw new RuntimeException();
  }
}
