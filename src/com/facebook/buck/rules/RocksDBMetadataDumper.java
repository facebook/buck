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

import com.google.common.base.Charsets;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

public class RocksDBMetadataDumper {
  private RocksDBMetadataDumper() {}

  public static void main(String[] args) throws RocksDBException {
    if (args.length < 1 || args.length > 2) {
      System.err.println("Usage: dump-rocksdb-metadata DB [TARGET]");
      System.exit(1);
    }
    String dbPath = args[0];
    String targetStr = "";
    if (args.length == 2) {
      targetStr = args[1] + "\0";
      if (!targetStr.startsWith("//")) {
        targetStr = "//" + targetStr;
      }
    }
    RocksDB.loadLibrary();
    try (Options options = new Options();
        RocksDB db = RocksDB.open(options, dbPath);
        RocksIterator it = db.newIterator()) {
      byte[] target = targetStr.getBytes(Charsets.UTF_8);
      it.seek(target);
      for (it.seek(target); it.isValid() && startsWith(it.key(), target); it.next()) {
        String[] key = new String(it.key(), Charsets.UTF_8).split("\0");
        String targetPiece = key[0];
        String metaPiece = key[1];
        String value = new String(it.value(), Charsets.UTF_8);
        System.out.format("%s %s %s%n", targetPiece, metaPiece, value);
      }
    }
  }

  private static boolean startsWith(byte[] src, byte[] cmp) {
    if (src.length < cmp.length) {
      return false;
    }
    for (int i = 0; i < cmp.length; i++) {
      if (src[i] != cmp[i]) {
        return false;
      }
    }
    return true;
  }
}
