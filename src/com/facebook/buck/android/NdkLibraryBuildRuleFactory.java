/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildRuleFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.util.List;

public class NdkLibraryBuildRuleFactory extends AbstractBuildRuleFactory<NdkLibrary.Builder> {

  private final Optional<String> ndkVersion;

  public NdkLibraryBuildRuleFactory(Optional<String> ndkVersion) {
    this.ndkVersion = Preconditions.checkNotNull(ndkVersion);
  }

  @Override
  public NdkLibrary.Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return NdkLibrary.newNdkLibraryRuleBuilder(params, ndkVersion);
  }

  @Override
  protected void amendBuilder(NdkLibrary.Builder builder, BuildRuleFactoryParams params) {
    // flags
    List<String> flags = params.getOptionalListAttribute("flags");
    for (String flag : flags) {
      builder.addFlag(flag);
    }
    builder.setIsAsset(params.getBooleanAttribute("is_asset"));
  }
}
