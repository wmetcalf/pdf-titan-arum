from click.testing import CliRunner

from titanarum import __version__
from titanarum.cli import cli


def test_version_command():
    result = CliRunner().invoke(cli, ["version"])
    assert result.exit_code == 0
    assert __version__ in result.output
