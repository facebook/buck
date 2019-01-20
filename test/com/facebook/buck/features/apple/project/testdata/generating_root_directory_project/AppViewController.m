/*
 * Copyright (c) 2015-present, Facebook, Inc. All rights reserved.
 *
 * The examples provided by Facebook are for non-commercial testing and evaluation
 * purposes only. Facebook reserves all rights not expressly granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * FACEBOOK BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

#import "AppViewController.h"

@interface AppViewController ()

@end

@implementation AppViewController {
    UILabel *_label;
    NSString *_helloString;
}

- (instancetype) initWithHelloString:(NSString *)helloString {
    if ((self = [super initWithNibName:nil bundle:nil])) {
        _helloString = helloString;
    }
    return self;
}

- (void) viewDidLoad {
    self.view.backgroundColor = [UIColor whiteColor];
    _label = [[UILabel alloc] initWithFrame:CGRectMake(0.0f, 0.0f, self.view.bounds.size.width, 80.0f)];
    _label.text = _helloString;
    _label.textAlignment = NSTextAlignmentCenter;
    _label.font = [UIFont boldSystemFontOfSize:32.0f];
    _label.adjustsFontSizeToFitWidth = YES;
    _label.backgroundColor = [UIColor clearColor];
    [self.view addSubview:_label];
}

- (void) viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    _label.center = CGPointMake(self.view.frame.size.width * 0.5f, self.view.frame.size.height * 0.5f);
}

@end
