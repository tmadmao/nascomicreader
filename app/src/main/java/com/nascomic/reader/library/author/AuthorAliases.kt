package com.nascomic.reader.library.author

/**
 * 作者别名：把「同一个人」的不同写法收敛到一个正名。
 *
 * 只做人工合并，不猜大小写、全半角或罗马音差异——那几种自动归一迟早会把两个真不同的人
 * 合成一个。表里的链在合并时压平，这里仍留一层解析与环保护：手写库或旧数据出现两层时
 * 不会死循环。
 */
object AuthorAliases {

    /**
     * 顺着别名表找正名。
     *
     * 遇到环（人为写坏的数据）就停在环上的第一个名字，别把整次扫描拖死。
     */
    fun resolve(name: String, aliases: Map<String, String>): String {
        var current = name
        val seen = HashSet<String>()
        while (true) {
            if (!seen.add(current)) return current
            current = aliases[current] ?: return current
        }
    }

    /**
     * 合并 [from] → [to] 之后，整张表应该是什么样。
     *
     * 指向 [from] 的旧名一并改指 [to]（A→B 时 A 落到 C，而不是留成 A→B→C）；
     * [from] 自己那一行写进表里，之后扫到旧写法才不会又长出一条。
     */
    fun merged(aliases: Map<String, String>, from: String, to: String): Map<String, String> {
        val canonical = resolve(to, aliases)
        val out = HashMap<String, String>(aliases.size + 1)
        aliases.forEach { (key, value) ->
            out[key] = if (value == from) canonical else value
        }
        if (from != canonical) out[from] = canonical else out.remove(from)
        return out
    }
}
