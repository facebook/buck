/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.thrift.RemoteDaemonicParserState;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/** A command for inspecting the artifact cache. */
public class ParserCacheCommand extends AbstractCommand {

  @Argument private List<String> arguments = new ArrayList<>();

  @Option(name = "--save", usage = "Save the parser cache state to the given file.")
  @Nullable
  private String saveFilename = null;

  @Option(name = "--load", usage = "Load the given parser cache from the given file.")
  @Nullable
  private String loadFilename = null;

  @Option(
    name = "--changes",
    usage =
        "File containing a JSON formatted list of changed files "
            + "that might invalidate the parser cache. The format of the file is an array of "
            + "objects with two keys: \"path\" and \"status\". "
            + "The path is relative to the root cell. "
            + "The status is the same as the one reported by \"hg status\" or \"git status\". "
            + "For example: \"A\", \"?\", \"R\", \"!\" or \"M\"."
  )
  @Nullable
  private String changesPath = null;

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {

    if (saveFilename != null && loadFilename != null) {
      params.getConsole().printErrorText("Can't use both --load and --save");
      return ExitCode.COMMANDLINE_ERROR;
    }

    if (saveFilename != null) {
      invalidateChanges(params);
      RemoteDaemonicParserState state = params.getParser().storeParserState(params.getCell());
      try (FileOutputStream fos = new FileOutputStream(saveFilename);
          ZipOutputStream zipos = new ZipOutputStream(fos)) {
        zipos.putNextEntry(new ZipEntry("parser_data"));
        try (ObjectOutputStream oos = new ObjectOutputStream(zipos)) {
          oos.writeObject(state);
        }
      }
    } else if (loadFilename != null) {
      try (FileInputStream fis = new FileInputStream(loadFilename);
          ZipInputStream zipis = new ZipInputStream(fis)) {
        ZipEntry entry = zipis.getNextEntry();
        Preconditions.checkState(entry.getName().equals("parser_data"));
        try (ObjectInputStream ois = new ObjectInputStream(zipis)) {
          RemoteDaemonicParserState state;
          try {
            state = (RemoteDaemonicParserState) ois.readObject();
          } catch (ClassNotFoundException e) {
            params.getConsole().printErrorText("Invalid file format");
            return ExitCode.COMMANDLINE_ERROR;
          }
          params.getParser().restoreParserState(state, params.getCell());
        }
      }
      invalidateChanges(params);

      ParserConfig configView = params.getBuckConfig().getView(ParserConfig.class);
      if (configView.isParserCacheMutationWarningEnabled()) {
        params
            .getConsole()
            .printErrorText(
                params
                    .getConsole()
                    .getAnsi()
                    .asWarningText(
                        "WARNING: Buck injected a parser state that may not match the local state."));
      }
    }

    return ExitCode.SUCCESS;
  }

  private void invalidateChanges(CommandRunnerParams params) throws IOException {
    if (changesPath == null) {
      return;
    }
    try (FileInputStream is = new FileInputStream(changesPath)) {
      JsonNode responseNode = ObjectMappers.READER.readTree(is);
      Iterator<JsonNode> iterator = responseNode.elements();
      while (iterator.hasNext()) {
        JsonNode item = iterator.next();
        String path = item.get("path").asText();
        String status = item.get("status").asText();

        boolean isAdded = false;
        boolean isRemoved = false;
        if (status.equals("A") || status.equals("?")) {
          isAdded = true;
        } else if (status.equals("R") || status.equals("!")) {
          isRemoved = true;
        }
        Path fullPath = params.getCell().getRoot().resolve(path).normalize();
        params.getParser().invalidateBasedOnPath(fullPath, isAdded || isRemoved);
      }
    }
  }

  @Override
  public String getShortDescription() {
    return "Load and save state of the parser cache";
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }
}
