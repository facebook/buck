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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.hash.HashCode;
import java.net.URI;
import java.util.Optional;
import org.immutables.value.Value;

public class RemoteFileDescription implements Description<RemoteFileDescriptionArg> {

  private final Downloader downloader;

  public RemoteFileDescription(Downloader downloader) {
    this.downloader = downloader;
  }

  @Override
  public Class<RemoteFileDescriptionArg> getConstructorArgType() {
    return RemoteFileDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      RemoteFileDescriptionArg args) {
    HashCode sha1;
    try {
      sha1 = HashCode.fromString(args.getSha1());
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException(
          e,
          "%s when parsing sha1 of %s",
          e.getMessage(),
          params.getBuildTarget().getUnflavoredBuildTarget().getFullyQualifiedName());
    }

    String out = args.getOut().orElse(params.getBuildTarget().getShortNameAndFlavorPostfix());

    RemoteFile.Type type = args.getType().orElse(RemoteFile.Type.DATA);
    if (type == RemoteFile.Type.EXECUTABLE) {
      return new RemoteFileBinary(params, downloader, args.getUrl(), sha1, out, type);
    }
    return new RemoteFile(params, downloader, args.getUrl(), sha1, out, type);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractRemoteFileDescriptionArg extends CommonDescriptionArg {
    URI getUrl();

    String getSha1();

    Optional<String> getOut();

    Optional<RemoteFile.Type> getType();
  }
}
