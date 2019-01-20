#import <Foundation/Foundation.h>
#import <TestLibrary/Foo.h>
#import <TestLibrary/TestLibrary-Swift.h>

int main(int argc, char *argv[]) {
  [Foo printSwiftObject];
  [Bar printSwiftStruct];
  return 0;
}
