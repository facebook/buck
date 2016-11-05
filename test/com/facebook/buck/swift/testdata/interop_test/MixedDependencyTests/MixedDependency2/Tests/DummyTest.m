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
#import <MixedDependency2/MixedDep2.h>

@interface FooXCTest : XCTestCase
@end

@implementation FooXCTest

- (void)testTwoPlusTwoEqualsFour {
  XCTAssertEqual(2 + 2, 4, @"Two plus two equals four");
}

- (void)testTestClassAnswer {
  XCTAssertEqualObjects([MD2TestClass answer], @"MD2TestClass");
}

- (void)testTestClass2Answer {
  XCTAssertEqualObjects([MD2TestClass2 answer], @"MD2TestClass2");
}

- (void)testFooBar {
  XCTAssertEqualObjects([MD2TestClass fooBar], @"Foo.bar");
}

- (void)testMd1Test {
  XCTAssertEqualObjects([MD2TestClass md1Test], @"MD1TestClass");
}
@end
