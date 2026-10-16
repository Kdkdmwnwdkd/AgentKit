package com.example.agentkit.tools

/**
 * W-7 P0-1：静态中文应用名字典（净室重写，零 Android 依赖）。
 *
 * 动机（用户真机反馈「跨应用操控不行，打开快手没成」的源码级深挖结论）：
 * 名称解析此前完全依赖 `app_list` 的 launchable 候选匹配——小模型给错名
 * （「快手极速版」「B站」）即 NotFound/Ambiguous，多耗 1-2 轮；若同时踩
 * maxSteps 截断，多步跨应用任务提前失败。参照 Operit `StandardUITools.kt:61-252`
 * 的 `APP_PACKAGES` 字典思路（设计参考，非代码复制）：**内置一份「名称→包名」
 * 事实数据表**，解析时优先查表，查不到再走候选匹配，最后按包名形态兜底直启。
 *
 * 设计约束（与仓库既有风格一致）：
 *  - **纯 Kotlin object + 纯函数**：无 Android 依赖，JVM harness 可直接断言；
 *  - **事实数据**：包名是公开事实（非创作），整理自 Operit 公开字典 + 各厂商
 *    公开包名；中文名与别名按日常称呼收录；
 *  - **字典只是加速器**：命中后仍走 `getLaunchIntentForPackage` 真校验，未安装
 *    应用会得到 no_launch_intent 明确失败，绝不「字典说有就有」；
 *  - 别名同包策略（学 Operit）：B站/哔哩哔哩 → tv.danmaku.bili 等。
 */

/**
 * 「名称（含别名）→ 包名」静态字典。
 *
 * 键为小写化后的名称/别名（[lookup] 会先把入参小写化），值为包名。
 * 收录范围：日常高频中文应用 + 少量英文常用（大小写不敏感）。
 */
internal object AppNameDictionary {

    /** 主表：名称 → 包名（键会再经 [lookup] 小写化匹配）。 */
    private val packages: Map<String, String> = linkedMapOf(
        // —— 社交通讯 ——
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "qq邮箱" to "com.tencent.androidqqmail",
        "邮箱" to "com.tencent.androidqqmail",
        "飞书" to "com.ss.android.lark",
        "钉钉" to "com.alibaba.android.rimet",
        "企业微信" to "com.tencent.wework",
        "微博" to "com.sina.weibo",
        // —— 内容社区 ——
        "小红书" to "com.xingin.xhs",
        "豆瓣" to "com.douban.frodo",
        "知乎" to "com.zhihu.android",
        "b站" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "kuaishou" to "com.smile.gifmaker",
        // —— 电商购物 ——
        "淘宝" to "com.taobao.taobao",
        "淘宝闪购" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "京东秒送" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "闲鱼" to "com.taobao.idlefish",
        // —— 本地生活 ——
        "高德地图" to "com.autonavi.minimap",
        "高德" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "美团" to "com.sankuai.meituan",
        "大众点评" to "com.dianping.v1",
        "饿了么" to "me.ele",
        // —— 出行差旅 ——
        "携程" to "ctrip.android.view",
        "铁路12306" to "com.MobileTicket",
        "12306" to "com.MobileTicket",
        "去哪儿" to "com.Qunar",
        "滴滴出行" to "com.sdu.did.psnger",
        "滴滴" to "com.sdu.did.psnger",
        // —— 视频娱乐 ——
        "腾讯视频" to "com.tencent.qqlive",
        "爱奇艺" to "com.qiyi.video",
        "优酷视频" to "com.youku.phone",
        "优酷" to "com.youku.phone",
        "芒果tv" to "com.hunantv.imgo.activity",
        "红果短剧" to "com.phoenix.read",
        // —— 音乐音频 ——
        "网易云音乐" to "com.netease.cloudmusic",
        "网易云" to "com.netease.cloudmusic",
        "qq音乐" to "com.tencent.qqmusic",
        "汽水音乐" to "com.luna.music",
        "喜马拉雅" to "com.ximalaya.ting.android",
        // —— 阅读 ——
        "番茄小说" to "com.dragon.read",
        "番茄免费小说" to "com.dragon.read",
        "七猫免费小说" to "com.kmxs.reader",
        "七猫" to "com.kmxs.reader",
        // —— 新闻资讯 ——
        "腾讯新闻" to "com.tencent.news",
        "今日头条" to "com.ss.android.article.news",
        "头条" to "com.ss.android.article.news",
        // —— 房产职场 ——
        "贝壳找房" to "com.lianjia.beike",
        "安居客" to "com.anjuke.android.app",
        "同花顺" to "com.hexin.plat.android",
        // —— 游戏 ——
        "星穹铁道" to "com.miHoYo.hkrpg",
        "崩坏：星穹铁道" to "com.miHoYo.hkrpg",
        "恋与深空" to "com.papegames.lysk.cn",
        // —— AI 与工具 ——
        "豆包" to "com.larus.nova",
        "keep" to "com.gotokeep.keep",
        "美柚" to "com.lingan.seeyou",
        "肯德基" to "com.yek.android.kfc.activitys",
        // —— 系统与海外常用 ——
        "设置" to "com.android.settings",
        "settings" to "com.android.settings",
        "chrome" to "com.android.chrome",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "地图" to "com.google.android.apps.maps",
        "whatsapp" to "com.whatsapp",
        "x" to "com.twitter.android",
        "twitter" to "com.twitter.android",
        "instagram" to "com.instagram.android",
        "telegram" to "org.telegram.messenger",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "facebook" to "com.facebook.katana",
        "line" to "jp.naver.line.android",
        "vlc" to "org.videolan.vlc"
    )

    /**
     * 查字典（**纯函数**）。
     *
     * 匹配顺序：原样 trim → 小写化后查表（中文不受影响，拉丁名大小写不敏感）。
     * 未命中返回 null——由调用方走候选匹配/包名兜底，**绝不把字典当唯一真相**。
     *
     * @param name 应用名或别名（如「快手」「B站」「kuaishou」）
     * @return 包名；未收录返回 null
     */
    fun lookup(name: String): String? {
        val t = name.trim()
        if (t.isEmpty()) return null
        packages[t]?.let { return it }
        return packages[t.lowercase()]
    }

    /** 字典条目数（harness 断言「字典非空且含快手」用）。 */
    val size: Int get() = packages.size
}

/**
 * 判断 target 是否「形如包名」（**纯函数**，W-7 P0-1 包名兜底判据）。
 *
 * 规则（学 Operit `PhoneAgent.kt:1228-1242` 末尾 `?: trimmed` 的思路）：
 * 至少含一个 '.'、无空白、且每段都是合法标识符段（字母/数字/下划线，段首不为数字）。
 * 「快手」「打开微信」等自然语言不会误判；「com.smile.gifmaker」直接放行。
 *
 * 为什么需要它：模型有时会「自学」包名（从 app_list 输出里抄），这些包名可能
 * 没有 launcher 入口而进不了候选列表，直接按包名试启是最后的诚实机会。
 */
internal fun looksLikePackageName(target: String): Boolean {
    val t = target.trim()
    if (t.isEmpty()) return false
    if (t.any { it.isWhitespace() }) return false
    if (!t.contains('.')) return false
    // 每段：非空、字母开头、仅字母数字下划线
    return t.split('.').all { seg ->
        seg.isNotEmpty() &&
            seg.first().isLetter() &&
            seg.all { it.isLetterOrDigit() || it == '_' }
    }
}

/**
 * 带静态字典 + 包名兜底的目标解析（**纯函数**，W-7 P0-1 的核心可测点）。
 *
 * 解析顺序（固定，优先精确）：
 *  1. **静态字典**（W-7 P0-1 新增）：[AppNameDictionary.lookup] 命中 →
 *     - 该包在候选列表中 → Unique（用候选的真实 label，信息更全）；
 *     - 不在候选 → Unique（合成条目，label 用入参名）——交由启动层
 *       `getLaunchIntentForPackage` 真校验：未安装/无入口会得到明确的
 *       no_launch_intent 失败，绝不「字典说有就有」。
 *  2. **候选匹配**：既有 [resolveAppTarget] 三级策略（精确包名 → 精确名称 → 模糊/前缀）。
 *  3. **包名兜底**（W-7 P0-1 新增）：仍 NotFound 且 [looksLikePackageName] 为真 →
 *     Unique（合成条目）直接试启（模型从 app_list 抄来的无入口包名也能启）。
 *
 * 歧义与无命中语义不变：歧义仍返回候选列表不擅自启动；无命中仍返回
 * NotFound（hint 由调用方给出，指向 app_list）。
 *
 * @param target 应用名、别名或包名
 * @param candidates 候选应用列表（通常来自 loadLaunchableApps）
 * @return [AppResolution]；纯函数，同样入参结果恒相等
 */
internal fun resolveAppTargetWithDict(target: String, candidates: List<AppEntry>): AppResolution {
    val t = target.trim()
    if (t.isEmpty()) return AppResolution.NotFound

    // 1. 静态字典优先
    val dictPkg = AppNameDictionary.lookup(t)
    if (dictPkg != null) {
        val hit = candidates.firstOrNull { it.packageName == dictPkg }
        return AppResolution.Unique(hit ?: AppEntry(label = t, packageName = dictPkg))
    }

    // 2. 既有候选匹配（精确包名 → 精确名称 → 模糊 → 前缀收窄）
    val r = resolveAppTarget(t, candidates)
    if (r !is AppResolution.NotFound) return r

    // 3. 包名形态兜底：形如包名但不在候选（如无 launcher 入口的应用），直接试启
    if (looksLikePackageName(t)) {
        return AppResolution.Unique(AppEntry(label = t, packageName = t))
    }
    return AppResolution.NotFound
}
