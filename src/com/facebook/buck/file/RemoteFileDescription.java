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
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.hash.HashCode;

import java.net.URI;

public class RemoteFileDescription implements Description<RemoteFileDescription.Arg> {

  public static final BuildRuleType TYPE = ImmutableBuildRuleType.of("remote_file");
  private final Downloader downloader;

  public RemoteFileDescription(Downloader downloader) {
    this.downloader = downloader;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    HashCode sha1 = HashCode.fromString(args.sha1);

    String out = args.out.or(params.getBuildTarget().getShortNameAndFlavorPostfix());

    return new RemoteFile(
        params,
        new SourcePathResolver(resolver),
        downloader,
        args.url,
        sha1,
        out);
  }

  @SuppressFieldNotInitialized
  public class Arg {
    public URI url;
    public String sha1;
    public Optional<String> out;
  }
}
