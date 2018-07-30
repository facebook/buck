/*
 * Copyright 2018-present Facebook, Inc. and Uber Technologies, Inc.
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
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OutputFilePublisher implements Supplier<Integer> {
  private final BuildRule buildRule;
  private final SourcePathResolver pathResolver;
  private final BuckEventBus buckEventBus;
  private final URL repoBase;
  private final String authorizationHeader;

  public OutputFilePublisher(
      BuildRule buildRule,
      SourcePathResolver pathResolver,
      BuckEventBus buckEventBus,
      URL repoBase,
      String authorizationHeader) {
    this.buildRule = buildRule;
    this.pathResolver = pathResolver;
    this.buckEventBus = buckEventBus;
    this.repoBase = repoBase;
    this.authorizationHeader = authorizationHeader;
  }

  @Override
  public Integer get() {
    Path output = pathResolver.getAbsolutePath(buildRule.getSourcePathToOutput());
    BuildTarget target = buildRule.getBuildTarget();
    if (Files.isDirectory(output)) {
      buckEventBus.post(
          ConsoleEvent.severe(
              "The output of "
                  + target.getFullyQualifiedName()
                  + " is a directory. Buck can only publish files."));
      return -1;
    }
    byte[] content;
    try {
      content = Files.readAllBytes(output);
    } catch (IOException e) {
      buckEventBus.post(
          ConsoleEvent.severe("Failed to read from " + output + ":\n" + e.getMessage()));
      return -1;
    }
    String sha = Hashing.sha256().hashBytes(content).toString();
    HttpUrl url = buildUrl(target, sha, output.getFileName().toString());
    Request request =
        new Request.Builder()
            .url(url)
            .header("Authorization", authorizationHeader)
            .header("X-Checksum-Sha256", sha)
            .put(RequestBody.create(MediaType.parse("application/octet-stream"), content))
            .build();
    try {
      OkHttpClient client = new OkHttpClient();
      Response response = client.newCall(request).execute();
      return response.code();
    } catch (IOException e) {
      buckEventBus.post(
          ConsoleEvent.severe("Failed to send " + output + " to " + url + ":\n" + e.getMessage()));
      return -1;
    }
  }

  HttpUrl buildUrl(BuildTarget target, String sha, String fileName) {
    return HttpUrl.get(repoBase)
        .newBuilder()
        .addPathSegments(target.getBasePath().toString())
        .addPathSegment(sha)
        .addPathSegment(fileName)
        .build();
  }
}
