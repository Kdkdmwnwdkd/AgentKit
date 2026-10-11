# 单词统计技能

统计工作区文件的单词数 / 行数 / 字符数。

- 通过 `use_skill` 触发后，由 `main.js` 自动执行。
- 默认读取 `notes.txt`，可在调用时通过 `path` 参数指定其它文件。
- 结果以 JSON 形式返回：{ path, words, lines, chars }。
