/*
 * Copyright (C) 2011 The Android Open Source Project
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

import java.io.Reader;
import java.io.StreamTokenizer;
import java.util.AbstractList;
import java.util.ArrayList;

public final class Foo {

    public void writeStreamTokenizerNval() {
        new StreamTokenizer((Reader) null).nval = 5;
    }

    public double readStreamTokenizerNval() {
        return new StreamTokenizer((Reader) null).nval;
    }

    public void callStringValueOf() {
        String.valueOf(5);
    }

    public void callIntegerValueOf() {
        Integer.valueOf("5");
    }

    public void callArrayListRemoveIndex() {
        new ArrayList<String>().remove(5);
    }

    public void callArrayListRemoveValue() {
        new ArrayList<String>().remove("5");
    }

    static class MyList<T> extends AbstractList<T> {
        @Override public T get(int index) {
            return null;
        }
        @Override public int size() {
            return 0;
        }
        @Override public boolean remove(Object o) {
            return false;
        }
    }
}
