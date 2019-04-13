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

package com.facebook.buck.file;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.file.downloader.Downloader;
import com.google.common.hash.HashCode;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * A description for downloading a single HttpFile (versus the combo logic contained in {@link
 * RemoteFileDescription}.
 */
public class HttpFileDescription implements DescriptionWithTargetGraph<HttpFileDescriptionArg> {
  @Override
  public Class<HttpFileDescriptionArg> getConstructorArgType() {
    return HttpFileDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      HttpFileDescriptionArg args) {

    HashCode sha256 =
        HttpCommonDescriptionArg.HttpCommonDescriptionArgHelpers.parseSha256(
            args.getSha256(), buildTarget);
    HttpCommonDescriptionArg.HttpCommonDescriptionArgHelpers.validateUris(
        args.getUrls(), buildTarget);

    String out = args.getOut().orElse(buildTarget.getShortNameAndFlavorPostfix());

    boolean executable = args.getExecutable().orElse(false);
    Downloader downloader =
        context.getToolchainProvider().getByName(Downloader.DEFAULT_NAME, Downloader.class);
    if (executable) {
      return new HttpFileBinary(
          buildTarget,
          context.getProjectFilesystem(),
          params,
          downloader,
          args.getUrls(),
          sha256,
          out);
    }
    return new HttpFile(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        downloader,
        args.getUrls(),
        sha256,
        out,
        false);
  }

  /** Args required for http_rule */
  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractHttpFileDescriptionArg extends HttpCommonDescriptionArg {
    Optional<Boolean> getExecutable();
  }
}
