/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.eden.cli;

import com.facebook.buck.eden.EdenClientPool;
import com.facebook.buck.eden.EdenMount;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class Sha1Command implements Command {

  @Option(
      name = "mount",
      aliases = {"-m"})
  private String mountPoint;

  @Argument private List<String> paths = new ArrayList<>();

  @Override
  public int run(EdenClientPool pool) throws EdenError, IOException, TException {
    Path mountPoint = Paths.get(this.mountPoint);
    EdenMount mount = EdenMount.createEdenMountForProjectRoot(mountPoint, pool).get();

    for (String path : paths) {
      Path entry = mountPoint.relativize(Paths.get(path));
      Sha1HashCode sha1 = mount.getSha1(entry);
      System.out.printf("%s %s\n", entry, sha1);
    }

    return 0;
  }
}
