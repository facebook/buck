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

package com.facebook.buck.event.listener;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class MoviePlayer {

  private static final int FRAME_STRIDE = 14;
  private final List<String> film;
  private final Path savedFilmStatePath;
  private int currentFilmLine;
  private int framesRemainingOnCurrentLine;

  public MoviePlayer(boolean useTheForce) {
    savedFilmStatePath = Paths.get(
        System.getProperty("user.home", "/"),
        "local",
        ".buckmovie");

    if (!useTheForce) {
      film = Collections.emptyList();
      return;
    }

    film = loadFilm();
    try {
      savedFilmStatePath.getParent().toFile().mkdirs();
      List<String> data = Files.lines(savedFilmStatePath).collect(Collectors.toList());
      currentFilmLine = Integer.parseInt(data.get(0));
      framesRemainingOnCurrentLine = Integer.parseInt(data.get(1));
    } catch (Exception e) {
      currentFilmLine = -FRAME_STRIDE;
      framesRemainingOnCurrentLine = 0;
    }
  }

  private static List<String> loadFilm() {
    URL url = Resources.getResource("buck_parser/starwars.txt");
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
      return reader.lines().collect(Collectors.toList());
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }

  public ImmutableList<String> renderFilmFrame() {
    ImmutableList<String> ret = ImmutableList.of();
    try {
      if (!film.isEmpty() && currentFilmLine < film.size()) {
        ImmutableList.Builder<String> lines = ImmutableList.builder();

        if (framesRemainingOnCurrentLine <= 0) {
          currentFilmLine += FRAME_STRIDE;
          framesRemainingOnCurrentLine = Integer.parseInt(film.get(currentFilmLine));
        }

        lines.add("");
        for (int i = 1; i < 14 && currentFilmLine < film.size(); i++) {
          lines.add(film.get(currentFilmLine + i));
        }

        framesRemainingOnCurrentLine--;

        ret = lines.build();

        Files.write(
            savedFilmStatePath,
            ImmutableList.of(
                Integer.toString(currentFilmLine),
                Integer.toString(framesRemainingOnCurrentLine)),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
      } else {
        Files.deleteIfExists(savedFilmStatePath);
      }

    } catch (Exception e) {
      makeLintStfu();
    }

    return ret;
  }

  private void makeLintStfu() {
    /* no op */
  }
}
