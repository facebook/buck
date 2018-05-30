/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.modern.Deserializer;
import com.facebook.buck.rules.modern.Deserializer.DataProvider;
import com.facebook.buck.rules.modern.Serializer;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class RelativeLinkArgTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TemporaryPaths other = new TemporaryPaths();

  @Test
  public void serialization() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path relativeDir = filesystem.getPath("some", "relative");
    RelativeLinkArg linkArg =
        new RelativeLinkArg(PathSourcePath.of(filesystem, relativeDir.resolve("libname")));
    CellPathResolver cellRoots = TestCellBuilder.createCellRoots(filesystem);
    Map<HashCode, byte[]> cache = new HashMap<>();
    // TODO(cjhopman): Extract these details of serializing+deserializing for use in other tests.
    HashCode hash =
        new Serializer(
                new SourcePathRuleFinder(new TestActionGraphBuilder()),
                cellRoots,
                (instance, data, children) -> {
                  HashCode childHash = Hashing.sipHash24().hashBytes(data);
                  cache.put(childHash, data);
                  return childHash;
                })
            .serialize(linkArg);

    ProjectFilesystem otherFilesystem =
        TestProjectFilesystems.createProjectFilesystem(other.getRoot());
    class SimpleProvider implements DataProvider {
      private final HashCode hash;

      SimpleProvider(HashCode hash) {
        this.hash = hash;
      }

      @Override
      public InputStream getData() {
        return new ByteArrayInputStream(cache.get(hash));
      }

      @Override
      public DataProvider getChild(HashCode hash) {
        return new SimpleProvider(hash);
      }
    }
    RelativeLinkArg deserialized =
        new Deserializer(name -> otherFilesystem, Class::forName, () -> null)
            .deserialize(new SimpleProvider(hash), RelativeLinkArg.class);

    assertEquals(
        String.format("-L%s -lname", other.getRoot().resolve(relativeDir)),
        deserialized.toString());
  }
}
