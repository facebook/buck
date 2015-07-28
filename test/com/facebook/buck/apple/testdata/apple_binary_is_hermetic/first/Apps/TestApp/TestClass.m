@interface TestClass

+ (int)answer;

@end

@implementation TestClass

+ (int)answer {
  return 42;
}

@end

int main() {
  return [TestClass answer];
}
