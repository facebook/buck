""" Module docstring """

def make_info(p):
    # Hasn't been assigned yet, it does not have a name
    print(p)
    return p

# Normally would be FakeInfo, but skylint complains. Appease the linter for now
fake_info = make_info(provider())
