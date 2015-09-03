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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * Download a file from a known location.
 */
public class DownloadStep implements Step {
  private final ProjectFilesystem filesystem;
  private final URI url;
  private final HashCode sha1;
  private final Path output;
  private final Downloader downloader;

  public DownloadStep(
      ProjectFilesystem filesystem,
      Downloader downloader,
      URI url,
      HashCode sha1,
      Path output) {
    this.filesystem = filesystem;
    this.downloader = downloader;
    this.url = url;
    this.sha1 = sha1;
    this.output = output;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    BuckEventBus eventBus = context.getBuckEventBus();
    try {
      Path resolved = filesystem.resolve(output);
      downloader.fetch(eventBus, url, resolved);

      HashCode readHash = Files.asByteSource(resolved.toFile()).hash(Hashing.sha1());
      if (!sha1.equals(readHash)) {
        eventBus.post(
            ConsoleEvent.severe(
                "Unable to download %s (hashes do not match. Expected %s, saw %s)",
                url,
                sha1,
                readHash));
        return -1;
      }
    } catch (IOException e) {
      eventBus.post(ConsoleEvent.severe("Unable to download: %s", url));
      return -1;
    } catch (HumanReadableException e) {
      eventBus.post(ConsoleEvent.severe(e.getHumanReadableErrorMessage(), e));
      return -1;
    }

    return 0;
  }

  @Override
  public String getShortName() {
    return "curl";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("curl %s -o '%s'", url, output);
  }
}
