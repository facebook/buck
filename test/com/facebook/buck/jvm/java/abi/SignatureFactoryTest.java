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

package com.facebook.buck.jvm.java.abi;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.google.common.base.Joiner;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

@RunWith(CompilerTreeApiParameterized.class)
public class SignatureFactoryTest extends DescriptorAndSignatureFactoryTestBase {
  @Test
  public void testAllTheThings() throws Exception {
    test(
        () -> {
          SignatureFactory signatureFactory = new SignatureFactory(new DescriptorFactory(elements));
          List<String> errors =
              getTestErrors(
                  field -> treatDependencyBoundsAsInterfaces(field.signature),
                  method -> treatDependencyBoundsAsInterfaces(method.signature),
                  type -> treatDependencyBoundsAsInterfaces(type.signature),
                  signatureFactory::getSignature);

          assertTrue("Signature mismatch!\n\n" + Joiner.on('\n').join(errors), errors.isEmpty());
        });
  }

  /**
   * We can't tell whether an inferred class is a class, interface, annotation, or enum. This is
   * problematic for expressing generic type bounds, because the bytecode is different depending on
   * whether it is a class or an interface. As it happens, it's safe (from the compiler's
   * perspective) to treat everything as an interface. This method is used to rework the "expected"
   * signature so that we can use the same test data for testing with and without deps.
   */
  private String treatDependencyBoundsAsInterfaces(String signature) {
    if (signature == null || isTestingWithDependencies()) {
      return signature;
    }
    SignatureWriter writer = new DependencyBoundsSignatureFixupWriter();
    new SignatureReader(signature).accept(writer);
    return writer.toString();
  }

  private static class DependencyBoundsSignatureFixupWriter extends SignatureWriter {
    @Override
    public SignatureVisitor visitClassBound() {
      return new DelayedBoundVisitor(this);
    }

    private class DelayedBoundVisitor extends SignatureVisitorWrapper {
      public DelayedBoundVisitor(SignatureVisitor sv) {
        super(Opcodes.ASM5, sv);
      }

      @Override
      public void visitClassType(String name) {
        if (name.equals("com/facebook/foo/Dependency")) {
          DependencyBoundsSignatureFixupWriter.this.visitInterfaceBound();
        } else {
          DependencyBoundsSignatureFixupWriter.this.visitClassBound();
        }

        DependencyBoundsSignatureFixupWriter.this.visitClassType(name);
      }
    }
  }
}
