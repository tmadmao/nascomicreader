package com.nascomic.reader.library.series

/**
 * 把同一部作品的各卷认出来。
 *
 * 三层漏斗：标准化精确匹配 → 路径聚合 → 编辑距离；
 * 每本只进一个组，先命中的组先拿走。结果带「高 / 低置信」与一句人话理由，
 * 高置信才允许自动落库，其余留给人确认。
 */
class SeriesDetector(
    private val threshold: (Int) -> Int = { len ->
        when {
            len < 4 -> 1
            len < 10 -> 2
            else -> 3
        }
    },
) {

    data class Entry(
        val bookId: Long,
        val title: String,
        /** 书所在目录；同一目录下的多本才有「像一套」的资格 */
        val parent: String,
    )

    data class Group(
        val name: String,
        val members: List<Entry>,
        /** 给人看的一句话理由 */
        val reason: String,
        /** true = 三项齐全（标准名同、作者同、卷号不同且都有），可以自动建 */
        val highConfidence: Boolean,
    )

    fun detect(entries: List<Entry>): List<Group> {
        val parsed = entries.map { it to VolumeNameParser.parse(it.title) }
        val taken = HashSet<Long>()
        val groups = ArrayList<Group>()

        // ① 标准化书名完全相同
        parsed.groupBy { it.second.normalized }
            .filterKeys { it.isNotEmpty() }
            .forEach { (_, list) ->
                if (list.size < 2) return@forEach
                val authors = list.mapNotNull { it.second.author }.toSet()
                val volumes = list.map { it.second.volume }
                val high = authors.size == 1 && volumes.none { it == null } &&
                    volumes.toSet().size == list.size
                groups += Group(
                    name = list.first().second.title,
                    members = list.map { it.first },
                    reason = if (high) {
                        "书名归一后相同、作者一致、卷号齐全"
                    } else {
                        "书名归一后相同，但作者或卷号对不上"
                    },
                    highConfidence = high,
                )
                list.forEach { taken += it.first.bookId }
            }

        // ② 同目录多本：目录名常就是系列名，但同名不同作者的情况要靠 ① 区分
        parsed.filter { it.first.bookId !in taken }
            .groupBy { it.first.parent }
            .forEach { (parent, list) ->
                if (list.size < 2) return@forEach
                groups += Group(
                    name = parent.substringAfterLast('/').ifEmpty { list.first().second.title },
                    members = list.map { it.first },
                    reason = "同一个文件夹下的多本，书名对不上",
                    highConfidence = false,
                )
                list.forEach { taken += it.first.bookId }
            }

        // ③ 编辑距离：只在前两步没认领的书之间比
        val rest = parsed.filter { it.first.bookId !in taken }
        val consumed = HashSet<Long>()
        for (i in rest.indices) {
            val (a, pa) = rest[i]
            if (a.bookId in consumed) continue
            val family = ArrayList<Entry>()
            family += a
            for (j in i + 1 until rest.size) {
                val (b, pb) = rest[j]
                if (b.bookId in consumed) continue
                if (pa.normalized.isEmpty() || pb.normalized.isEmpty()) continue
                val limit = threshold(minOf(pa.normalized.length, pb.normalized.length))
                if (distance(pa.normalized, pb.normalized) <= limit) {
                    family += b
                    consumed += b.bookId
                }
            }
            if (family.size > 1) {
                consumed += a.bookId
                groups += Group(
                    name = pa.title,
                    members = family,
                    reason = "书名相近（仅细微差异）",
                    highConfidence = false,
                )
            }
        }

        return groups
    }

    /** 绝对编辑距离，不做归一化：理由要能讲给用户听 */
    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
