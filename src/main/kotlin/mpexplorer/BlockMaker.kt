package mpexplorer

import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.slf4j.LoggerFactory

class ProposedBlock(
    val txns: List<MemPoolEntry>,
    val size: Int
)

class BlockMaker(val mempool: ObservableList<MemPoolEntry>) {
    val log = LoggerFactory.getLogger(BlockMaker::class.java)
    val contents = FXCollections.observableArrayList<MemPoolEntry>()

    fun calculate(maxBlockSize: Int, maxPrioSize: Int) {
        contents.setAll(makeBlock(maxBlockSize, maxPrioSize).txns)   // Satoshi used a weird definition of megabyte
    }

    fun makeBlock(maxSize: Int, maxPrioSize: Int = maxSize / 20): ProposedBlock {
        // TODO: Consider making this code async
        var entries = mempool.filterNot { it.height > 0 || it.fee == -1L }.toArrayList()
        val origSize = entries.size
        val selected = linkedListOf<MemPoolEntry>()
        var bytesSelected = 0

        fun select(max: Int): Int {
            var cursor = 0
            while (cursor < entries.size && bytesSelected + entries[cursor].msgSize < max) {
                selected.add(entries[cursor])
                bytesSelected += entries[cursor].msgSize
                cursor++
            }
            return cursor
        }

        // Sort by priority.
        entries.sort { left, right -> -left.priority.compareTo(right.priority) }
        val numSelected = select(maxPrioSize)

        // Now re-sort by fee
        entries = entries.subList(numSelected, entries.size).toArrayList()
        entries.sort { left, right -> -left.feePerByte.compareTo(right.feePerByte) }
        select(maxSize)

        log.info("Selected ${selected.size} transactions out of $origSize that total $bytesSelected bytes")

        return ProposedBlock(selected, bytesSelected)
    }
}