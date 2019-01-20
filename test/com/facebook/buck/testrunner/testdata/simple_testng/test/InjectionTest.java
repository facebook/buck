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

package com.example;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import com.example.InjectionTest.InjectionTestModule;
import com.example.InjectionTest.TestInterface;
import com.example.InjectionTest.TestInterfaceImpl;
import com.google.inject.AbstractModule;
import javax.inject.Inject;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

@Guice(modules = InjectionTestModule.class)
public class InjectionTest {
  private final TestInterface injectedInterface;

  @Inject
  public InjectionTest(TestInterface injectedInterface) {
    this.injectedInterface = injectedInterface;
  }

  @Test
  public void injectionWorks() {
    assertThat(injectedInterface.getValue(), is(5));
  }

  public static final class InjectionTestModule extends AbstractModule {
    @Override
    protected void configure() {
      bind(TestInterface.class).to(TestInterfaceImpl.class);
    }
  }

  public static interface TestInterface {
    int getValue();
  }

  public static class TestInterfaceImpl implements TestInterface {
    @Override
    public int getValue() {
      return 5;
    }
  }
}
