/*
 * Copyright (C) 2007 The Android Open Source Project
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

public class Blort
{
    // Empty switch statement. (Note: This is the same as a default-only
    // switch statement, since under the covers every switch statement
    // has a default of some sort.)
    public int test1(int x) {
        switch (x) {
            // This space intentionally left blank.
        }

        return 0;
    }

    // Single element.
    public int test2(int x) {
        switch (x) {
            case 0: return 0;
        }

        return 1;
    }

    // Single element: Integer.MIN_VALUE.
    public int test3(int x) {
        switch (x) {
            case Integer.MIN_VALUE: return 0;
        }

        return 1;
    }

    // Single element: Integer.MAX_VALUE.
    public int test4(int x) {
        switch (x) {
            case Integer.MAX_VALUE: return 0;
        }

        return 1;
    }

    // Two elements: 0 and Integer.MIN_VALUE.
    public int test5(int x) {
        switch (x) {
            case 0: return 0;
            case Integer.MIN_VALUE: return 1;
        }

        return 2;
    }

    // Two elements: 0 and Integer.MAX_VALUE.
    public int test6(int x) {
        switch (x) {
            case 0: return 0;
            case Integer.MAX_VALUE: return 1;
        }

        return 2;
    }

    // Two elements: Integer.MIN_VALUE and Integer.MAX_VALUE.
    public int test7(int x) {
        switch (x) {
            case Integer.MIN_VALUE: return 0;
            case Integer.MAX_VALUE: return 1;
        }

        return 2;
    }

    // Two elements: Large enough to be packed but such that 32 bit
    // threshold calculations could overflow.
    public int test8(int x) {
        switch (x) {
            case 0: return 0;
            case 0x4cccccc8: return 1;
        }

        return 2;
    }
}
