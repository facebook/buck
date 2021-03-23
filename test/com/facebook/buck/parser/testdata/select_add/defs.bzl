def test():
    for left in [(), [], select({"DEFAULT": []}), select({"DEFAULT": []}) + select({"DEFAULT": []})]:
        for right in [(), [], select({"DEFAULT": []}), select({"DEFAULT": []}) + select({"DEFAULT": []})]:
            # For some reason we allow select + tuple, but do not allow list + tuple
            if left == () and right == []:
                continue
            if left == [] and right == ():
                continue
            _ = left + right
