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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

public class MapDBBuildInfoStore implements BuildInfoStore {
  private static final char KEY_SEP = ' ';
  private static final char KEY_END = KEY_SEP + 1;

  private final DB db;
  private final BTreeMap<String, String> map;

  public MapDBBuildInfoStore(ProjectFilesystem filesystem) {
    Path dbPath =
        filesystem
            .getRootPath()
            .resolve(filesystem.getBuckPaths().getScratchDir().resolve("metadata.db"));
    db = DBMaker.fileDB(dbPath.toString()).fileMmapEnableIfSupported().transactionEnable().make();
    map = db.treeMap("map", Serializer.STRING, Serializer.STRING).createOrOpen();
  }

  @Override
  public void close() {
    db.close();
  }

  @Override
  public Optional<String> readMetadata(BuildTarget buildTarget, String key) {
    String result = map.get(makeKey(buildTarget, key));
    return Optional.ofNullable(result);
  }

  @Override
  public void updateMetadata(BuildTarget buildTarget, Map<String, String> metadata)
      throws IOException {
    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      map.put(makeKey(buildTarget, entry.getKey()), entry.getValue());
    }
  }

  @Override
  public void deleteMetadata(BuildTarget buildTarget) throws IOException {
    for (String key :
        map.subMap(keyStart(buildTarget), true, keyEnd(buildTarget), false).keySet()) {
      map.remove(key);
    }
  }

  private static String makeKey(BuildTarget buildTarget, String key) {
    return buildTarget.getFullyQualifiedName() + KEY_SEP + key;
  }

  private static String keyStart(BuildTarget buildTarget) {
    return buildTarget.getFullyQualifiedName() + KEY_SEP;
  }

  private static String keyEnd(BuildTarget buildTarget) {
    return buildTarget.getFullyQualifiedName() + KEY_END;
  }
}
