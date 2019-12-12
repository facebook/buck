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

package com.facebook.buck.query;

import static org.junit.Assert.assertEquals;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class QueryNormalizerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  @Parameters(method = "getNormalizeData")
  @TestCaseName("normalizePattern{0}")
  public void normalize(String testName, String query, String expected) throws Exception {
    assertEquals(expected, QueryNormalizer.normalize(query));
  }

  private Object getNormalizeData() {
    return new Object[] {
      new Object[] {"NoSubstitution", "query", "query"},
      new Object[] {
        "NoSubstitutionMultiline",
        "bar" + System.lineSeparator() + "baz",
        "bar" + System.lineSeparator() + "baz"
      },
      new Object[] {
        "SingleSet",
        "query(%Ss)" + System.lineSeparator() + "bar" + System.lineSeparator() + "baz",
        "query(set('bar' 'baz'))"
      },
      new Object[] {
        "SingleSetManyPatterns",
        "query1(%Ss) query2(%Ss)" + System.lineSeparator() + "bar" + System.lineSeparator() + "baz",
        "query1(set('bar' 'baz')) query2(set('bar' 'baz'))"
      },
      new Object[] {
        "ManySets",
        "%Ss %Ss"
            + System.lineSeparator()
            + "bar"
            + System.lineSeparator()
            + "--"
            + System.lineSeparator()
            + "baz",
        "set('bar') set('baz')"
      }
    };
  }

  @Test
  @Parameters(method = "getErrorData")
  @TestCaseName("normalizePattern{0}")
  public void error(String testName, String query) throws Exception {
    thrown.expect(QueryException.class);
    QueryNormalizer.normalize(query);
  }

  private Object getErrorData() {
    return new Object[] {
      new Object[] {
        "MoreSetsThanExpected",
        "%Ss"
            + System.lineSeparator()
            + "bar"
            + System.lineSeparator()
            + "--"
            + System.lineSeparator()
            + "baz"
      },
      new Object[] {
        "LessSetsThanExpected",
        "%Ss %Ss %Ss"
            + System.lineSeparator()
            + "bar"
            + System.lineSeparator()
            + "--"
            + System.lineSeparator()
            + "baz"
      },
      new Object[] {"SubPresentButNoSetsProvided", "query(%Ss)"}
    };
  }
}
