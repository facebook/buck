SomeInfo = provider(fields = [
    "aaa",
    "bbb",
])

# Using provider in the same file where provider is defined
some_info = SomeInfo(aaa = 1, bbb = True)
