/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.apple.common;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;

/** Flavors for Apple Swift compilation actions and metadata. */
public class AppleFlavors {
  public static final Flavor SWIFT_COMPILE_FLAVOR = InternalFlavor.of("apple-swift-compile");
  public static final Flavor SWIFT_COMMAND_FLAVOR = InternalFlavor.of("apple-swift-command");
  public static final Flavor SWIFT_EXPORTED_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("apple-swift-objc-generated-header");
  public static final Flavor SWIFT_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("apple-swift-private-objc-generated-header");
  public static final Flavor SWIFT_UNDERLYING_MODULE_FLAVOR =
      InternalFlavor.of("apple-swift-underlying-module");
  public static final Flavor SWIFT_UNDERLYING_VFS_OVERLAY_FLAVOR =
      InternalFlavor.of("apple-swift-underlying-vfs-overlay");
  public static final Flavor SWIFT_METADATA_FLAVOR = InternalFlavor.of("swift-metadata");
  public static final Flavor SWIFT_UNDERLYING_MODULE_INPUT_FLAVOR =
      InternalFlavor.of("swift-underlying-module-input");
}
