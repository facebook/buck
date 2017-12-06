/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class ProvisioningProfileStoreFactory {
  private static final Logger LOG = Logger.get(ProvisioningProfileStoreFactory.class);

  public static ProvisioningProfileStore fromSearchPath(
      final ProcessExecutor executor,
      final ImmutableList<String> readCommand,
      final Path searchPath) {
    LOG.debug("Provisioning profile search path: " + searchPath);
    return ProvisioningProfileStore.of(
        MoreSuppliers.memoize(
            () -> {
              final Builder<ProvisioningProfileMetadata> profilesBuilder = ImmutableList.builder();
              try {
                Files.walkFileTree(
                    searchPath.toAbsolutePath(),
                    new SimpleFileVisitor<Path>() {
                      @Override
                      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                          throws IOException {
                        if (file.toString().endsWith(".mobileprovision")) {
                          try {
                            ProvisioningProfileMetadata profile =
                                ProvisioningProfileMetadata.fromProvisioningProfilePath(
                                    executor, readCommand, file);
                            profilesBuilder.add(profile);
                          } catch (IOException | IllegalArgumentException e) {
                            LOG.error(e, "Ignoring invalid or malformed .mobileprovision file");
                          } catch (InterruptedException e) {
                            throw new IOException(e);
                          }
                        }

                        return FileVisitResult.CONTINUE;
                      }
                    });
              } catch (NoSuchFileException e) {
                LOG.debug(e, "The folder containing provisioning profile was not found.");
              } catch (IOException e) {
                if (e.getCause() instanceof InterruptedException) {
                  LOG.error(e, "Interrupted while searching for mobileprovision files");
                } else {
                  LOG.error(e, "Error while searching for mobileprovision files");
                }
              }
              return profilesBuilder.build();
            }));
  }
}
