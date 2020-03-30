""" Module docstring """

# Little pass through function to ensure we can create providers when not
# at the top level. As long as they are assigned to a variable at the top
# level before being used, you're good to go. Still probably not a great practice.
def pass_through(fields):
    return provider(fields = fields)

# Normally would be FirstInfo, but skylint complains. Appease the linter for now
first_info = pass_through(fields = [
    "first",
    "empty",
])

SecondInfo = provider(fields = [
    "second",
    "empty",
])

def validate_first_info(first_info):
    if "first_info(" not in repr(first_info):
        fail("expected name first_info for {}".format(first_info))
    if first_info.first != "first_value":
        fail('expected first_info.first == "first_value", got {}'.format(first_info.first))
    if first_info.empty != None:
        fail("expected first_info.empty == None, got {}".format(first_info.empty))

def validate_second_info(second_info):
    if "SecondInfo(" not in repr(second_info):
        fail("expected name SecondInfo for {}".format(second_info))
    if second_info.second != "second_value":
        fail('expected second_info.second == "second_value", got {}'.format(second_info.second))
    if second_info.empty != None:
        fail("expected second_info.empty == None, got {}".format(second_info.empty))
