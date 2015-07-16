/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dx.multidex;

/**
 * Test that DexMerge works by merging dex files, and then loading them into
 * the current VM.
 */
public final class MainDexListTest {

    public static void main(String[] args) throws Exception {
        // simplify the javac classpath by not depending directly on 'dalvik.system' classes
        ClassLoader mainLoader = (ClassLoader) Class.forName("dalvik.system.PathClassLoader")
                .getConstructor(String.class, ClassLoader.class)
                .newInstance("out/classes.dex", MainDexListTest.class.getClassLoader());
        ClassLoader secondaryLoader = (ClassLoader) Class.forName("dalvik.system.PathClassLoader")
                .getConstructor(String.class, ClassLoader.class)
                .newInstance("out/classes2.dex", MainDexListTest.class.getClassLoader());

        mainLoader.loadClass("testdata.InMainDex");
        secondaryLoader.loadClass("testdata.InSecondaryDex");
       try {
           secondaryLoader.loadClass("testdata.InMainDex");
           throw new AssertionError();
       } catch (ClassNotFoundException e) {
           // expected
       }
       try {
           mainLoader.loadClass("testdata.InSecondaryDex");
           throw new AssertionError();
       } catch (ClassNotFoundException e) {
           // expected
       }

    }

}
