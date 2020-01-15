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

package com.facebook.buck.core.starlark.rule;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.skylark.function.FakeSkylarkUserDefinedRuleFactory;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.syntax.EvalException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SkylarkDescriptionArgTest {

  @Rule public ExpectedException expected = ExpectedException.none();

  @Test
  public void throwsWhenInvalidFieldIsRequested() throws EvalException, LabelSyntaxException {

    SkylarkDescriptionArg arg =
        new SkylarkDescriptionArg(FakeSkylarkUserDefinedRuleFactory.createSimpleRule());

    expected.expect(NullPointerException.class);
    arg.getPostCoercionValue("baz");
  }

  @Test
  public void throwsWhenSettingWithAnInvalidName() throws LabelSyntaxException, EvalException {
    SkylarkDescriptionArg arg =
        new SkylarkDescriptionArg(FakeSkylarkUserDefinedRuleFactory.createSimpleRule());

    expected.expect(IllegalStateException.class);
    expected.expectMessage("it was not one of the attributes");
    arg.setPostCoercionValue("not_declared", 1);
  }

  @Test
  public void throwsWhenSettingAfterBuilding() throws LabelSyntaxException, EvalException {
    SkylarkDescriptionArg arg =
        new SkylarkDescriptionArg(FakeSkylarkUserDefinedRuleFactory.createSimpleRule());
    arg.setPostCoercionValue("name", "ohmy");
    arg.build();

    expected.expect(IllegalStateException.class);
    expected.expectMessage("after building an instance");
    arg.setPostCoercionValue("baz", 1);
  }

  @Test
  public void getsValuesThatHaveBeenSet() throws LabelSyntaxException, EvalException {
    SkylarkDescriptionArg arg =
        new SkylarkDescriptionArg(FakeSkylarkUserDefinedRuleFactory.createSimpleRule());

    arg.setPostCoercionValue("baz", 1);
    assertEquals(1, arg.getPostCoercionValue("baz"));
  }

  @Test
  public void getsLabelsAndLicenses() throws LabelSyntaxException, EvalException {
    ImmutableSortedSet<DefaultBuildTargetSourcePath> licenses =
        ImmutableSortedSet.of(
            DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:LICENSE")),
            DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:LICENSE2")));
    SkylarkDescriptionArg arg =
        new SkylarkDescriptionArg(FakeSkylarkUserDefinedRuleFactory.createSimpleRule());
    arg.setPostCoercionValue("labels", ImmutableSortedSet.of("foo", "bar"));
    arg.setPostCoercionValue("licenses", licenses);

    assertEquals(ImmutableSortedSet.of("bar", "foo"), arg.getLabels());
    assertEquals(licenses, arg.getLicenses());
  }
}
