import yaml


def load_yaml(file: str, mode: str = "r", encoding: str = "utf8") -> dict:
    """
    读取yaml文件数据

    :param file:
    :param mode:
    :param encoding:
    :return:
    """
    with open(file, mode, encoding=encoding) as f:
        data = yaml.safe_load(f.read())
        return data
