from pathlib import Path

from utils.loads import load_yaml

workdir = Path(__file__).parent.parent

settings = load_yaml(workdir.joinpath("settings.yml").__str__())
