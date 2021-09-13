/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.edenfs;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.config.Inis;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Utility methods for Eden */
public class EdenUtil {
  private static final Logger LOG = Logger.get(EdenUtil.class);

  /**
   * Find a Eden path, like socket or root. On Linux or Mac it's a symlink file like.eden/socket to
   * the file on Eden mount root folder. But on Windows, the information was stored a TOML config on
   * .eden directory of the Eden mount root
   *
   * @param directoryInEdenFsMount a directory in Eden fs mount, not necessary to be the Eden mount
   *     folder
   * @param key the key to look for.
   * @return the real path of the Eden path, return Optional.empty() if the file not exists
   */
  public static Optional<Path> getPathFromEdenConfig(Path directoryInEdenFsMount, String key) {

    Optional<Path> edenFile = Optional.empty();

    if (Platform.detect() == Platform.WINDOWS) {
      // On Windows platform, Eden config (socket path or root path) was set in .eden/config
      Optional<Path> dotEdenConfig = findDotEdenConfig(directoryInEdenFsMount);
      if (dotEdenConfig.isPresent()) {
        try {
          ImmutableMap<String, ImmutableMap<String, String>> parsedDotEdenConfig =
              Inis.read(dotEdenConfig.get().toUri().toURL());
          if (parsedDotEdenConfig.containsKey("Config")
              && parsedDotEdenConfig.get("Config").containsKey(key)) {
            edenFile =
                Optional.of(
                    directoryInEdenFsMount
                        .getFileSystem()
                        .getPath(parsedDotEdenConfig.get("Config").get(key).replace("\"", "")));
          }
        } catch (IOException e) {
          return Optional.empty();
        }
      }
    } else {
      edenFile = Optional.of(directoryInEdenFsMount.resolve(".eden").resolve(key));
    }

    if (!edenFile.isPresent() || !Files.exists(edenFile.get())) {
      return Optional.empty();
    }
    if (Files.isSymbolicLink(edenFile.get())) {
      try {
        return Optional.of(Files.readSymbolicLink(edenFile.get()));
      } catch (IOException e) {
        return Optional.empty();
      }
    }

    return Optional.of(edenFile.get());
  }

  private static Optional<Path> findDotEdenConfig(Path directoryInEdenFsMount) {
    if (!Files.isDirectory(directoryInEdenFsMount) || directoryInEdenFsMount.getParent() == null) {
      return Optional.empty();
    }
    Path dotEdenConfig = directoryInEdenFsMount.resolve(".eden/config");
    if (Files.exists(dotEdenConfig)) {
      return Optional.of(dotEdenConfig);
    } else {
      return findDotEdenConfig(directoryInEdenFsMount.getParent());
    }
  }

  /** Is eden alive on given socket path? */
  static boolean pingSocket(Path unixSocket) {
    try (ReconnectingEdenClient edenClient =
        new ReconnectingEdenClient(unixSocket, new DefaultClock())) {
      edenClient.getEdenClient().getPid();
      return true;
    } catch (EdenError | IOException | TException e) {
      LOG.debug("Could not connect Eden client via socket: " + unixSocket);
      return false;
    }
  }
}
