import random


def weighted_choice(weight_dict):
    total = sum(weight_dict.values())
    if total == 0:
        return None
    selected = random.randint(0, total - 1)
    for key, weight in weight_dict.items():
        if selected < weight:
            return key
        selected -= weight
    assert False
