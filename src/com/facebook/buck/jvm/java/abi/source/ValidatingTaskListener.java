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

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfoFactory.SourceOnlyAbiRuleInfo;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTaskProxyImpl;
import com.facebook.buck.jvm.java.plugin.api.BuckJavacTaskProxy;
import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.tools.Diagnostic;

/**
 * A {@link TaskListener} that is used during full compilation to validate the guesses made by
 * source-based ABI generation.
 */
public class ValidatingTaskListener implements TaskListener {
  private final BuckJavacTask javacTask;
  private final List<CompilationUnitTree> compilationUnits = new ArrayList<>();
  private final SourceOnlyAbiRuleInfo ruleInfo;
  private final Supplier<Boolean> errorsExist;
  private final Diagnostic.Kind messageKind;

  @Nullable private InterfaceValidator validator;
  private int enterDepth = 0;
  private boolean annotationProcessing = false;

  public ValidatingTaskListener(
      BuckJavacTaskProxy task,
      SourceOnlyAbiRuleInfo ruleInfo,
      Supplier<Boolean> errorsExist,
      Diagnostic.Kind messageKind) {
    this.javacTask = ((BuckJavacTaskProxyImpl) task).getInner();
    this.ruleInfo = ruleInfo;
    this.errorsExist = errorsExist;
    this.messageKind = messageKind;
  }

  private InterfaceValidator getValidator() {
    // We can't do this on construction, because the Task might not be fully initialized yet
    if (validator == null) {
      validator = new InterfaceValidator(messageKind, javacTask, ruleInfo);
    }

    return Preconditions.checkNotNull(validator);
  }

  @Override
  public void started(TaskEvent e) {
    TaskEvent.Kind kind = e.getKind();

    if (kind == TaskEvent.Kind.ENTER) {
      enterDepth += 1;
    } else if (kind == TaskEvent.Kind.ANNOTATION_PROCESSING) {
      annotationProcessing = true;
    }
  }

  @Override
  public void finished(TaskEvent e) {
    TaskEvent.Kind kind = e.getKind();
    if (kind == TaskEvent.Kind.PARSE) {
      CompilationUnitTree compilationUnit = e.getCompilationUnit();
      compilationUnits.add(compilationUnit);
    } else if (kind == TaskEvent.Kind.ENTER) {
      enterDepth -= 1;
      // We wait until we've received all enter events so that the validation time shows up
      // separately from compiler enter time in the traces. We wait until after annotation
      // processing so we catch all the types.
      if (!annotationProcessing && enterDepth == 0 && !errorsExist.get()) {
        getValidator().validate(compilationUnits);
      }
    } else if (kind == TaskEvent.Kind.ANNOTATION_PROCESSING) {
      annotationProcessing = false;
    }
  }
}
