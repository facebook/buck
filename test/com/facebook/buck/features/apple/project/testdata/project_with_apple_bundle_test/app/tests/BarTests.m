//
//  Copyright (c) 2016-2018 Uber Technologies, Inc. All rights reserved.
//

#import <XCTest/XCTest.h>

#import <Utility.h>

@interface BarTests : XCTestCase

@end


@implementation BarTests

- (void)testBarString
{
    Utility *utility = [[Utility alloc] init];
    XCTAssertTrue([[utility barString] isEqualToString:@"bar"], @"Test should have access to binary sources");
}

@end
