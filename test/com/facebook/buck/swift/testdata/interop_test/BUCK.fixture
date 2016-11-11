apple_binary(
  name='MainBinary',
  headers= [
      'MainApp/AppDelegate.h'
  ],
  srcs = glob([
      'MainApp/AppDelegate.m',
      'MainApp/main.m',
      'MainApp/*.swift',
  ]),
  deps = [
    '//MixedDependencyTests/ObjcDependency1:ObjcDependency1',
    '//MixedDependencyTests/MixedDependency1:MixedDependency1',
    '//MixedDependencyTests/MixedDependency2:MixedDependency2',
    '//MixedDependencyTests/MixedDependency3:MixedDependency3',
  ],
  frameworks = [
    '$SDKROOT/System/Library/Frameworks/UIKit.framework',
    '$SDKROOT/System/Library/Frameworks/Foundation.framework',
  ],
)

apple_bundle(
    name='MainBundle',
    binary=':MainBinary',
    extension='app',
    info_plist='MainApp/Info.plist',
    product_name = 'MainBundle',
    info_plist_substitutions={
        'PRODUCT_BUNDLE_IDENTIFIER': 'com.uber.test1',
        'EXECUTABLE_NAME': 'MainBundle'
    }
)
