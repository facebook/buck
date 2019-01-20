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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.io.Files;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Download a file from a known location. */
public class DownloadStep implements Step {
  private final ProjectFilesystem filesystem;
  private final URI canonicalUri;
  private final ImmutableList<URI> additionalUris;
  private final FileHash expectedHash;
  private final Path output;
  private final Downloader downloader;

  /**
   * Creates an instance of {@link DownloadStep}
   *
   * @param filesystem The filesystem to use for download files
   * @param downloader The downloader to use to fetch files
   * @param canonicalUri The primary uri to try, and the one used in display text
   * @param additionalUris If the original download fails, try to download from these mirrors
   * @param expectedHash The expected expectedHash of the file
   * @param output Where to output the file inside of the filesystem
   */
  public DownloadStep(
      ProjectFilesystem filesystem,
      Downloader downloader,
      URI canonicalUri,
      ImmutableList<URI> additionalUris,
      FileHash expectedHash,
      Path output) {
    this.filesystem = filesystem;
    this.downloader = downloader;
    this.canonicalUri = canonicalUri;
    this.additionalUris = additionalUris;
    this.expectedHash = expectedHash;
    this.output = output;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    BuckEventBus eventBus = context.getBuckEventBus();
    Path resolved = filesystem.resolve(output);

    ImmutableList<URI> allUris =
        ImmutableList.<URI>builder().add(canonicalUri).addAll(additionalUris).build();

    boolean success = false;
    for (int i = 0; i < allUris.size(); i++) {
      success = downloader.fetch(eventBus, allUris.get(i), resolved);
      if (success) {
        break;
      } else if (i + 1 < allUris.size()) {
        reportTryingNextUri(eventBus, allUris.get(i), allUris.get(i + 1));
      }
    }

    if (!success) {
      return StepExecutionResult.of(
          reportFailedDownload(eventBus, allUris.get(allUris.size() - 1)));
    }

    HashCode readHash = Files.asByteSource(resolved.toFile()).hash(expectedHash.getHashFunction());
    if (!expectedHash.getHashCode().equals(readHash)) {
      eventBus.post(
          ConsoleEvent.severe(
              "Unable to download %s (hashes do not match. Expected %s, saw %s)",
              canonicalUri, expectedHash, readHash));
      return StepExecutionResult.of(-1);
    }

    return StepExecutionResults.SUCCESS;
  }

  private void reportTryingNextUri(BuckEventBus eventBus, URI currentUri, URI nextUri) {
    if (canonicalUri.equals(currentUri)) {
      eventBus.post(
          ConsoleEvent.severe(
              "Unable to download %s, trying to download from %s instead", currentUri, nextUri));
    } else {
      eventBus.post(
          ConsoleEvent.severe(
              "Unable to download %s (canonical URI: %s), trying to download from %s instead",
              currentUri, canonicalUri, nextUri));
    }
  }

  private int reportFailedDownload(BuckEventBus eventBus, URI uri) {
    if (canonicalUri.equals(uri)) {
      eventBus.post(ConsoleEvent.severe("Unable to download: %s", uri));
    } else {
      eventBus.post(
          ConsoleEvent.severe("Unable to download %s (canonical URI: %s)", uri, canonicalUri));
    }
    return -1;
  }

  @Override
  public String getShortName() {
    return "curl";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "curl %s -o '%s'",
        canonicalUri, MorePaths.pathWithUnixSeparators(filesystem.resolve(output)));
  }
}
