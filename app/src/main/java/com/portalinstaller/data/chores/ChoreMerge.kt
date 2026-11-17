/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.chores

/**
 * Deterministic replica merge for [ChoreState]. Pure and order-free: `merge(a, b) == merge(b, a)`
 * and `merge(a, merge(a, b)) == merge(a, b)`, so Portals converge however exchanges interleave.
 * Per entry, last writer wins by (seq, deleted / status rank, by); the canonical text is a final
 * tie-break so even a malformed peer can't make two replicas pick differently.
 */
object ChoreMerge {
    private val kidOrder = compareBy<ChoreKid>({ it.seq }, { it.deleted }, { it.by }, { ChoreCodec.canonicalKid(it) })
    private val choreOrder = compareBy<Chore>({ it.seq }, { it.deleted }, { it.by }, { ChoreCodec.canonicalChore(it) })
    private val tickOrder = compareBy<ChoreTick>({ it.seq }, { it.status.rank }, { it.by }, { ChoreCodec.canonicalTick(it) })

    fun merge(a: ChoreState, b: ChoreState): ChoreState {
        if (a === b || a == b) return a
        return ChoreState(
                kids = mergeMaps(a.kids, b.kids, kidOrder),
                chores = mergeMaps(a.chores, b.chores, choreOrder),
                ticks = mergeMaps(a.ticks, b.ticks, tickOrder),
        )
    }

    /** Positive when [a] should replace [b]. */
    fun compareTicks(a: ChoreTick, b: ChoreTick): Int = tickOrder.compare(a, b)

    private fun <T> mergeMaps(a: Map<String, T>, b: Map<String, T>, order: Comparator<T>): Map<String, T> {
        if (a == b) return a
        return (a.keys + b.keys).associateWith { id ->
            val x = a[id]
            val y = b[id]
            when {
                x == null -> y!!
                y == null -> x
                else -> maxOf(x, y, order)
            }
        }
    }
}
