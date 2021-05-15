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

package com.facebook.buck.skylark.function.packages;

import static org.junit.Assert.*;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.Structure;
import org.junit.Test;

public class StructImplTest {

  private static class OtherStruct extends Structure {

    @Nullable
    @Override
    public Object getField(String name) throws EvalException {
      switch (name) {
        case "x":
          return StarlarkInt.of(1);
        case "y":
          return true;
        default:
          return null;
      }
    }

    @Override
    public ImmutableCollection<String> getFieldNames() {
      return ImmutableList.of("x", "y");
    }

    @Nullable
    @Override
    public String getErrorMessageForUnknownField(String field) {
      return null;
    }
  }

  @Test
  public void toJson() throws Exception {
    StructImpl struct =
        StructImpl.create(StructProvider.STRUCT, ImmutableMap.of("a", StarlarkInt.of(1)), null);
    assertEquals("{\"a\":1}", struct.toJson());
  }

  @Test
  public void toJsonOtherStruct() throws EvalException {
    StructImpl struct =
        StructImpl.create(StructProvider.STRUCT, ImmutableMap.of("a", new OtherStruct()), null);
    assertEquals("{\"a\":{\"x\":1,\"y\":true}}", struct.toJson());
  }

  @Test
  public void toJsonDictFields() throws EvalException {
    StructImpl struct =
        StructImpl.create(
            StructProvider.STRUCT,
            ImmutableMap.of("a", Dict.immutableOf("x", StarlarkInt.of(1))),
            null);
    assertEquals("{\"a\":{\"x\":1}}", struct.toJson());
  }
}
