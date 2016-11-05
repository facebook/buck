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

#import <XCTest/XCTest.h>
#import <ObjcDependency1/ObjcDep1.h>

@interface FooXCTest : XCTestCase
@end

@implementation FooXCTest

- (void)testTwoPlusTwoEqualsFour {
  XCTAssertEqual(2 + 2, 4, @"Two plus two equals four");
}

- (void)testTestClassAnswer {
  XCTAssertEqualObjects([OD1TestClass answer], @"OD1TestClass");
}

- (void)testTestClass2Answer {
  XCTAssertEqualObjects([OD1TestClass2 answer], @"OD1TestClass2");
}

@end
