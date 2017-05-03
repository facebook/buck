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

package com.facebook.buck.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.EnumOptionHandler;
import org.kohsuke.args4j.spi.Setter;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

public class MapDBCommandLine {

  enum MapType {
    TREE,
    HASH,
  }

  enum Command {
    PUT,
    GET,
    DELETE,
    SCAN,
  }

  public static class MapTypeOptionHandler extends EnumOptionHandler<MapType> {
    public MapTypeOptionHandler(
        CmdLineParser parser, OptionDef option, Setter<? super MapType> setter) {
      super(parser, option, setter, MapType.class);
    }
  }

  public static class CommandOptionHandler extends EnumOptionHandler<Command> {
    public CommandOptionHandler(
        CmdLineParser parser, OptionDef option, Setter<? super Command> setter) {
      super(parser, option, setter, Command.class);
    }
  }

  @Option(name = "--db", usage = "path to database file", required = true)
  private String dbPath;

  @Option(name = "--map", usage = "name of map within database")
  private String mapName = "map";

  @Option(name = "--type", handler = MapTypeOptionHandler.class, usage = "type of map")
  private MapType mapType = MapType.TREE;

  @Argument(index = 0, handler = CommandOptionHandler.class, required = true, metaVar = "COMMAND")
  private Command command;

  @Argument(index = 1)
  private List<String> arguments = new ArrayList<>();

  Map<String, String> getMap(DB db) {
    switch (mapType) {
      case HASH:
        return db.hashMap(mapName, Serializer.STRING, Serializer.STRING).createOrOpen();
      case TREE:
        return db.treeMap(mapName, Serializer.STRING, Serializer.STRING).createOrOpen();
    }
    throw new IllegalArgumentException();
  }

  private void runCommand(DB db) {
    Map<String, String> map = getMap(db);
    switch (command) {
      case PUT:
        if (arguments.size() != 2) {
          throw new IllegalArgumentException("PUT requires exactly two arguments (KEY and VALUE)");
        }
        map.put(arguments.get(0), arguments.get(1));
        break;
      case GET:
        if (arguments.size() != 1) {
          throw new IllegalArgumentException("GET requires exactly one argument (KEY)");
        }
        String value = map.get(arguments.get(0));
        if (value != null) {
          System.out.println(value);
        } else {
          System.err.format("%s: key not found\n", arguments.get(0));
        }
        break;
      case DELETE:
        if (arguments.size() != 1) {
          throw new IllegalArgumentException("DELETE requires exactly one argument (KEY)");
        }
        map.remove(arguments.get(0));
        break;
      case SCAN:
        for (Map.Entry<String, String> e : map.entrySet()) {
          System.out.format("%s : %s\n", e.getKey(), e.getValue());
        }
        break;
    }
  }

  private void runMain(String[] args) {
    CmdLineParser parser = new CmdLineParser(this);
    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      // XXX: do something with it
      System.err.println(e.getLocalizedMessage());
      parser.printUsage(System.err);
      System.exit(1);
    }
    try (DB db = DBMaker.fileDB(dbPath.toString()).fileMmapEnableIfSupported().make()) {
      runCommand(db);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getLocalizedMessage());
      System.exit(1);
    }
  }

  public static void main(String[] args) {
    new MapDBCommandLine().runMain(args);
  }
}
