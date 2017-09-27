import typing


class Repository:
    """Represents a code repository with .buckconfig file."""
    def __init__(self, cell_roots: typing.Dict[str, str]):
        self.cell_roots = cell_roots

    def get_cell_path(self, cell: str) -> str:
        """Returns the path where provided cell is located."""
        return self.cell_roots[cell]
