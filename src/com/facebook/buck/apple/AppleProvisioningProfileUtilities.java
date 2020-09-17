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

package com.facebook.buck.apple;

/** Utilities related to provisioning profiles on Apple platforms. */
public class AppleProvisioningProfileUtilities {

  private AppleProvisioningProfileUtilities() {}

  /** Returns the name of the provisioning profile in the final .app bundle. */
  public static String getProvisioningProfileFileNameInBundle() {
    return "embedded.mobileprovision";
  }

  /** Returns the destination of the provisioning profile */
  public static AppleBundleDestination getProvisioningProfileBundleDestination() {
    return AppleBundleDestination.RESOURCES;
  }
}
