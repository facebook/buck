/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Description for an osx_resource rule which copies resource files to
 * the "Resources" subdirectory directory of the built OS X application.
 */
public class OsxResourceDescription implements Description<AppleResourceDescriptionArg> {

  public static final BuildRuleType TYPE = new BuildRuleType("osx_resource");
  private static final Optional<Path> OUTPUT_RESOURCE_PATH = Optional.of(Paths.get("Resources"));

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public AppleResourceDescriptionArg createUnpopulatedConstructorArg() {
    return new AppleResourceDescriptionArg();
  }

  @Override
  public AppleResource createBuildable(BuildRuleParams params, AppleResourceDescriptionArg args) {
    return new AppleResource(
        params.getBuildTarget(),
        new DefaultDirectoryTraverser(),
        args,
        OUTPUT_RESOURCE_PATH);
  }
}
