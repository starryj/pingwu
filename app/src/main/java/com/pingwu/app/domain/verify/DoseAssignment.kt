package com.pingwu.app.domain.verify

/**
 * 按「计划颗数」做整体最优分配（《18-方案调整》§3 ③）。
 *
 * 逐颗找最近模板在两种药颜色接近时会随机归属，出现「A 少一颗、B 多一颗」这种假错误；
 * 这里利用已知的应服颗数，把检测到的药丸整体分配给各药种，使「总颜色距离」最小。
 * 药种越多收益越明显。纯 Kotlin、无 Android 依赖，可单测。
 */
internal object DoseAssignment {

    /** 一个药种要分几颗（capacity = 应服颗数）。 */
    data class Slot(val drugId: Long, val capacity: Int)

    data class Result(
        /** 与输入药丸同序：分配到的药种下标（对应 slots）；-1 = 没分出去（多出来的药丸）。 */
        val assignment: IntArray,
        val totalCost: Double,
        /** true = 穷举超预算，退化为贪心（结果仍可用，只是不保证全局最优）。 */
        val fallback: Boolean,
    ) {
        fun counts(slots: List<Slot>): Map<Long, Int> {
            val out = LinkedHashMap<Long, Int>()
            for (s in slots) out[s.drugId] = 0
            for (index in assignment) {
                if (index < 0 || index >= slots.size) continue
                val id = slots[index].drugId
                out[id] = (out[id] ?: 0) + 1
            }
            return out
        }
    }

    /**
     * @param costs 每颗药丸到每个药种的颜色距离（内层数组与 slots 同序）。
     * @param slots 各药种容量。
     */
    fun solve(costs: List<DoubleArray>, slots: List<Slot>): Result {
        val n = costs.size
        if (n == 0 || slots.isEmpty()) {
            return Result(IntArray(n) { -1 }, 0.0, false)
        }
        val totalCapacity = slots.sumOf { it.capacity }
        // 药丸比容量少：每颗都必须分出去（容量是上限），分不满的药种由判定层报「少了」。
        // 药丸不少于容量：只分出去 totalCapacity 颗，剩下的留 -1，判定层报「多了」。
        val mustAssignAll = n < totalCapacity
        val solver = Solver(costs, slots, mustAssignAll, totalCapacity)
        solver.run()
        if (solver.budgetExceeded || solver.bestCost == Double.MAX_VALUE) {
            return greedy(costs, slots, mustAssignAll, totalCapacity)
        }
        return Result(solver.bestAssign.copyOf(), solver.bestCost, false)
    }

    /** 穷举预算用尽时的兜底：每次挑「当前最便宜的一对」贪心分配。 */
    private fun greedy(
        costs: List<DoubleArray>,
        slots: List<Slot>,
        mustAssignAll: Boolean,
        totalCapacity: Int,
    ): Result {
        val n = costs.size
        val out = IntArray(n) { -1 }
        val remaining = IntArray(slots.size) { slots[it].capacity }
        val target = if (mustAssignAll) n else totalCapacity
        var assigned = 0
        var total = 0.0
        while (assigned < target) {
            var bestPill = -1
            var bestSlot = -1
            var bestCost = Double.MAX_VALUE
            for (i in 0 until n) {
                if (out[i] >= 0) continue
                for (s in slots.indices) {
                    if (remaining[s] <= 0) continue
                    val c = costs[i].getOrElse(s) { Double.MAX_VALUE }
                    if (c < bestCost) {
                        bestCost = c
                        bestPill = i
                        bestSlot = s
                    }
                }
            }
            if (bestPill < 0) break
            out[bestPill] = bestSlot
            remaining[bestSlot]--
            assigned++
            if (bestCost < Double.MAX_VALUE) total += bestCost
        }
        return Result(out, total, true)
    }

    /** 回溯枚举：按药种顺序，为每个药种从未分配的药丸里挑 capacity 颗。 */
    private class Solver(
        private val costs: List<DoubleArray>,
        private val slots: List<Slot>,
        private val mustAssignAll: Boolean,
        private val totalCapacity: Int,
    ) {
        private val n = costs.size
        private val current = IntArray(n) { -1 }
        val bestAssign = IntArray(n) { -1 }
        var bestCost = Double.MAX_VALUE
            private set
        var budgetExceeded = false
            private set
        private var nodes = 0

        fun run() = solveSlot(0, 0.0)

        private fun solveSlot(slotIndex: Int, cost: Double) {
            if (budgetExceeded) return
            if (++nodes > MAX_NODES) {
                budgetExceeded = true
                return
            }
            // 剪枝：当前代价已经不小于已知最优，后面只会更贵。
            if (cost >= bestCost) return
            if (slotIndex == slots.size) {
                if (isComplete()) {
                    bestCost = cost
                    current.copyInto(bestAssign)
                }
                return
            }
            val capacity = slots[slotIndex].capacity
            if (capacity <= 0) {
                solveSlot(slotIndex + 1, cost)
                return
            }
            pick(0, capacity, slotIndex, cost)
        }

        private fun pick(from: Int, need: Int, slotIndex: Int, cost: Double) {
            if (budgetExceeded) return
            if (need == 0) {
                solveSlot(slotIndex + 1, cost)
                return
            }
            for (i in from until n) {
                if (n - i < need) break
                if (current[i] >= 0) continue
                current[i] = slotIndex
                pick(i + 1, need - 1, slotIndex, cost + costs[i].getOrElse(slotIndex) { 0.0 })
                current[i] = -1
            }
        }

        private fun isComplete(): Boolean {
            var assigned = 0
            for (i in 0 until n) if (current[i] >= 0) assigned++
            return if (mustAssignAll) assigned == n else assigned == totalCapacity
        }
    }

    /** 节点预算：正常一顿 2–5 颗、2–4 个药种，枚举量只有几十到几千，远用不到这个上限。 */
    private const val MAX_NODES = 200_000
}
