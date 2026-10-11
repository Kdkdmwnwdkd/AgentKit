// 入口函数：SkillExecutor 会把 (input, agent) 传入。
//   input : use_skill 收到的参数 JSON 字符串（含 name / path）
//   agent : 安全运行时桥，提供 agent.readFile / agent.writeFile / agent.http / agent.notify / agent.log
(input, agent) => {
  const args = JSON.parse(input || "{}");
  const path = args.path || "notes.txt";
  const content = agent.readFile(path);

  // read_file 出错时返回以 ⛔ / ⚠️ 开头的信息，直接透传给模型
  if (content.startsWith("⛔") || content.startsWith("⚠️")) {
    return content;
  }

  const words = content.trim().split(/\s+/).filter(function (w) { return w.length > 0; });
  const lines = content.split("\n").length;
  agent.log("counted " + words.length + " words in " + path);

  return JSON.stringify({
    path: path,
    words: words.length,
    lines: lines,
    chars: content.length
  });
}
