/*
 * Copyright 2015-present Facebook, Inc.
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


#import "InterfaceController.h"


@interface InterfaceController()

@property (weak, nonatomic) IBOutlet WKInterfaceButton *button;
@property (weak, nonatomic) IBOutlet WKInterfacePicker *picker;

@end


@implementation InterfaceController
{
  NSArray *_pickerItems;
}

- (void)awakeWithContext:(id)context {
  [super awakeWithContext:context];

  [self _initializePickerItems];
  [_picker setItems:_pickerItems];
}

- (void)_initializePickerItems
{
  WKPickerItem *itemIOS = [[WKPickerItem alloc] init];
  itemIOS.title = @"iPhoneOS";

  WKPickerItem *itemWatchOS = [[WKPickerItem alloc] init];
  itemIOS.title = @"WatchOS";

  WKPickerItem *itemOSX = [[WKPickerItem alloc] init];
  itemIOS.title = @"MacOSX";

  _pickerItems = @[itemIOS, itemWatchOS, itemOSX];
}

- (void)willActivate {
  // This method is called when watch view controller is about to be visible to user
  [super willActivate];
}

- (void)didDeactivate {
  // This method is called when watch view controller is no longer visible
  [super didDeactivate];
}

- (IBAction)buttonTapped:(id)sender
{
  [_button setTitle:@"Tapped!!"];
}

- (IBAction)pickerChanged:(NSInteger)value
{
  [_button setTitle:[NSString stringWithFormat:@"%@ selected", [_pickerItems[value] title]]];
}

@end
