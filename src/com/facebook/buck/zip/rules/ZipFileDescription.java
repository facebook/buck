/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.zip.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import org.immutables.value.Value;

public class ZipFileDescription
    implements Description<ZipFileDescriptionArg>, VersionPropagator<ZipFileDescriptionArg> {

  @Override
  public Class<ZipFileDescriptionArg> getConstructorArgType() {
    return ZipFileDescriptionArg.class;
  }

  @Override
  public Zip createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ZipFileDescriptionArg args) {
    return new Zip(
        new SourcePathRuleFinder(context.getBuildRuleResolver()),
        buildTarget,
        context.getProjectFilesystem(),
        args.getOut(),
        args.getSrcs(),
        args.getFlatten(),
        args.getMergeSourceZips());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractZipFileDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs {
    @Value.Default
    default String getOut() {
      return getName() + ".zip";
    }

    @Value.Default
    default boolean getFlatten() {
      return false;
    }

    @Value.Default
    default boolean getMergeSourceZips() {
      return true;
    }
  }
}
