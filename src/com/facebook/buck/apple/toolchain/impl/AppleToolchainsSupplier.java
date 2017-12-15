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

package com.facebook.buck.apple.toolchain.impl;

import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.toolchain.ToolchainDescriptor;
import com.facebook.buck.toolchain.ToolchainSupplier;
import java.util.Arrays;
import java.util.Collection;
import org.pf4j.Extension;

@Extension
public class AppleToolchainsSupplier implements ToolchainSupplier {

  @Override
  public Collection<ToolchainDescriptor<?>> getToolchainDescriptor() {
    return Arrays.asList(
        ToolchainDescriptor.of(
            AppleDeveloperDirectoryProvider.DEFAULT_NAME,
            AppleDeveloperDirectoryProvider.class,
            AppleDeveloperDirectoryProviderFactory.class),
        ToolchainDescriptor.of(
            AppleDeveloperDirectoryForTestsProvider.DEFAULT_NAME,
            AppleDeveloperDirectoryForTestsProvider.class,
            AppleDeveloperDirectoryForTestsProviderFactory.class),
        ToolchainDescriptor.of(
            AppleToolchainProvider.DEFAULT_NAME,
            AppleToolchainProvider.class,
            AppleToolchainProviderFactory.class),
        ToolchainDescriptor.of(
            AppleSdkLocation.DEFAULT_NAME, AppleSdkLocation.class, AppleSdkLocationFactory.class),
        ToolchainDescriptor.of(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            AppleCxxPlatformsProvider.class,
            AppleCxxPlatformsProviderFactory.class),
        ToolchainDescriptor.of(
            CodeSignIdentityStore.DEFAULT_NAME,
            CodeSignIdentityStore.class,
            CodeSignIdentityStoreFactory.class),
        ToolchainDescriptor.of(
            ProvisioningProfileStore.DEFAULT_NAME,
            ProvisioningProfileStore.class,
            ProvisioningProfileStoreFactory.class));
  }
}
