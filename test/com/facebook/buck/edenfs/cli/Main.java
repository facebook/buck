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

package com.facebook.buck.edenfs.cli;

import com.facebook.buck.edenfs.EdenClientPool;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

/** Utility for communicating with Eden from the command line. */
public final class Main {

  /** Utility class: do not instantiate. */
  private Main() {}

  public static void main(String... args) {
    int exitCode = run(args);
    System.exit(exitCode);
  }

  private static int run(String... args) {
    Args argsObject = new Args();
    CmdLineParser parser = new CmdLineParser(argsObject);
    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      e.printStackTrace();
      return 1;
    }

    // The default path for the Eden socket is ~/local/.eden/socket. Ultimately, it will be
    // possible to query for this via `eden config --get`.
    Path socketFile = Paths.get(System.getProperty("user.home"), "local/.eden/socket");
    Optional<EdenClientPool> pool = EdenClientPool.newInstanceFromSocket(socketFile);
    if (!pool.isPresent()) {
      System.err.println("Could not connect to Eden.");
      return 1;
    }

    try {
      return argsObject.run(pool.get());
    } catch (EdenError | IOException | TException e) {
      e.printStackTrace();
      return 1;
    }
  }
}
