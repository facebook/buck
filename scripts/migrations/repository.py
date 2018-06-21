from typing import Dict, Optional


class Repository:
    """Represents a code repository with .buckconfig file."""

    def __init__(self, root: str, cell_roots: Dict[str, str]) -> None:
        self.root = root
        self.cell_roots = cell_roots

    def get_cell_path(self, cell: Optional[str]) -> str:
        """Returns the path where provided cell is located."""
        if not cell:
            return self.root
        assert cell in self.cell_roots, cell + " is not a known root"
        return self.cell_roots[cell]
