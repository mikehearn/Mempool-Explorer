package mpexplorer

import humanize.Humanize
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.binding.Bindings.createStringBinding
import javafx.beans.binding.Bindings.format
import javafx.beans.binding.Bindings.size
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleLongProperty
import javafx.collections.FXCollections
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.cell.TextFieldTableCell
import javafx.stage.Stage
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.async
import org.bitcoinj.core.*
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.store.MemoryBlockStore
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.utils.BtcFormat
import org.bitcoinj.utils.Threading
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.BitSet
import java.util.Timer
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import kotlin.concurrent.schedule
import kotlin.properties.Delegates

data class MemPoolEntry(
        val fee: Long,
        val msgSize: Int,
        val msgSizeForPriority: Int,
        val priority: Long,
        val hash: Sha256Hash,
        val height: Int = 0
) {
    val feePerByte: Long get() = if (fee == -1L) -1L else fee / msgSize
}

class FeeColumn(val formatter: BtcFormat) : TextFieldTableCell<MemPoolEntry, Long>() {
    override fun updateItem(item: Long?, empty: Boolean) {
        super.updateItem(item, empty)
        if (empty)
            setText("")
        else if (item!! == -1L)
            setText("•")
        else
            setText(formatter.format(Coin.valueOf(item)))
    }
}

class UIController {
    val log = LoggerFactory.getLogger(javaClass<UIController>())

    FXML public var mempoolTable: TableView<MemPoolEntry> = later()
    FXML public var blockMakerTable: TableView<MemPoolEntry> = later()
    FXML public var numTxnsLabel: Label = later()
    FXML public var numTxnsInLastBlockLabel: Label = later()
    FXML public var mempoolBytesLabel: Label = later()

    FXML public var blockSizeSlider: Slider = later()
    FXML public var priorityAreaSizeSlider: Slider = later()
    FXML public var blockSizeLabel: Label = later()
    FXML public var priorityAreaSizeLabel: Label = later()

    var blockMaker: BlockMaker = later()

    suppress("UNCHECKED_CAST")
    public fun init(app: App) {
        configureTable(mempoolTable)
        configureTable(blockMakerTable)

        val hashCol = mempoolTable.getColumns()[5] as TableColumn<MemPoolEntry, Sha256Hash>
        hashCol.setCellFactory { column ->
            object : TextFieldTableCell<MemPoolEntry, Sha256Hash>() {
                init {
                    setOnMouseClicked { ev ->
                        if (ev.getClickCount() == 2)
                            app.getHostServices().showDocument("https://tradeblock.com/blockchain/tx/${getText()}")
                    }
                }
            }
        }

        app.uiState.useWith {
            blockMaker = BlockMaker(mempool)
            wireSorted(mempoolTable, mempool)
            wireSorted(blockMakerTable, blockMaker.contents)

            numTxnsLabel.textProperty() bind format(numTxnsLabel.getText(), size(mempool))
            numTxnsInLastBlockLabel.textProperty() bind format(numTxnsInLastBlockLabel.getText(), numTxnsInLastBlock)

            mempoolBytesLabel.textProperty() bind format(mempoolBytesLabel.getText(),
                createStringBinding(Callable { Humanize.binaryPrefix(mempoolBytes.get()) }, mempoolBytes)
            )
        }

        // Wire up the block maker sliders:
        // - Priority area cannot be bigger than the block size itself
        priorityAreaSizeSlider.maxProperty() bind blockSizeSlider.valueProperty()
        // - Block size label shows size in humanized bytes
        blockSizeLabel.textProperty() bind createStringBinding(Callable {
            Humanize.binaryPrefix(blockSizeSlider.getValue().toInt())
        }, blockSizeSlider.valueProperty())
        // - Priority area size label shows size in humanized bytes
        priorityAreaSizeLabel.textProperty() bind createStringBinding(Callable {
            Humanize.binaryPrefix(priorityAreaSizeSlider.getValue().toInt())
        }, priorityAreaSizeSlider.valueProperty())
        // - Editing either slider results in a recalculation of the block
        blockSizeSlider.valueProperty().addListener { o -> update() }
        priorityAreaSizeSlider.valueProperty().addListener { o -> update() }
    }

    FXML
    public fun onCalcPressed(ev: ActionEvent) {
        update()
    }

    private fun update() {
        blockMaker.calculate(blockSizeSlider.getValue().toInt(), priorityAreaSizeSlider.getValue().toInt())
    }
}

class App : Application() {
    val log = LoggerFactory.getLogger(javaClass<App>())

    var controller: UIController by Delegates.notNull()
    val ctx = Context(MainNetParams.get())
    var store: MemoryBlockStore by Delegates.notNull()
    var pg: PeerGroup by Delegates.notNull()

    val uiState = UIThreadBox(object {
        val mempool = FXCollections.observableArrayList<MemPoolEntry>()
        var mempoolBytes = SimpleLongProperty()
        val numTxnsInLastBlock = SimpleIntegerProperty()
    })

    // Input values we haven't found yet. When every entry in inputValues is found, we can calculate the
    // fee and insert it into the mempool list.
    data class PartialFeeData(
            val totalOut: Long,
            val inputValues: LongArray,    // -1 if unknown
            val inputHeights: LongArray,   // 0 if unknown
            val forTx: MemPoolEntry,
            val mempoolIdx: Int
    )
    data class AwaitingInput(val index: Int, val pfd: PartialFeeData, var utxoQueryDone: Boolean = false)

    // Put in a box just in case we want to shift calculations off the UI thread.
    val state = ThreadBox(object {
        val mapMemPool = hashMapOf<Sha256Hash, Transaction>()
        val mapEntries = hashMapOf<Sha256Hash, Int>()  // index in mempool array
        val mapAwaiting = hashMapOf<TransactionOutPoint, AwaitingInput>()
        var lookupImmediately = false
    })

    override fun start(stage: Stage) {
        BriefLogFormatter.init()

        val loader = FXMLLoader(javaClass<App>().getResource("main.fxml"))
        val scene = Scene(loader.load())
        controller = loader.getController()
        controller.init(this)
        stage.setScene(scene)
        stage.setTitle("Mempool Explorer")
        stage.setMaximized(true)
        stage.show()

        // Make async run callbacks on the UI thread by default.
        Kovenant.context {
            callbackContext.dispatcher = JFXDispatcher()
        }

        Threading.USER_THREAD = Executor {
            Platform.runLater(it)
        }

        startMemPoolDownload()
    }

    private fun startMemPoolDownload() {
        async {
            Context.propagate(ctx)
            val params = ctx.getParams()
            val now = Instant.now().getEpochSecond()
            store = MemoryBlockStore(params)
            CheckpointManager.checkpoint(params, CheckpointManager.openStream(params), store, now)

            val blockchain = BlockChain(ctx, store)
            pg = PeerGroup(ctx, blockchain)

            blockchain.addListener(object : AbstractBlockChainListener() {
                var sizeOfLastBlock: Long = 0L
                var numTxns: Int = 0

                override fun receiveFromBlock(tx: Transaction, block: StoredBlock, blockType: AbstractBlockChain.NewBlockType, relativityOffset: Int) {
                    if (tx.isCoinBase()) return
                    if (blockType != AbstractBlockChain.NewBlockType.BEST_CHAIN) return
                    sizeOfLastBlock += tx.getMessageSize()
                    numTxns++
                    val height = block.getHeight()
                    uiState.useWith {
                        state.useWith {
                            val index: Int? = mapEntries[tx.getHash()]
                            if (index != null)
                                mempool[index] = mempool[index].copy(height = height)
                        }
                    }
                }

                override fun notifyNewBestBlock(block: StoredBlock) {
                    val size = sizeOfLastBlock
                    val num = numTxns
                    sizeOfLastBlock = 0L
                    numTxns = 0
                    uiState.useWith {
                        mempoolBytes -= size
                        numTxnsInLastBlock.set(num)
                    }
                }
            })

            //pg.addAddress(InetAddress.getByName("plan99.net"))
            //pg.setUseLocalhostPeerWhenPossible(false)

            pg.setFastCatchupTimeSecs(now)
            pg.setUserAgent("Mempool Explorer", "1.0")
            pg.start()
            val tracker = DownloadProgressTracker()
            pg.startBlockChainDownload(tracker)
            tracker.await()
            pg.waitForPeers(1).toPromise()
        }.unwrap() success {
            val peer = it[0]

            // Wait 5 seconds to give us time to download the full mempool and resolve internally before starting
            // to do block chain UTXO lookups.
            Timer().schedule(5000) {
                state.useWith { lookupImmediately = true }
                doUTXOLookups()
            }

            // Respond to network events:
            peer.addEventListener(object : AbstractPeerEventListener() {
                override fun onTransaction(peer: Peer, t: Transaction) {
                    processTX(t)
                }
            })
            peer.sendMessage(MemoryPoolMessage())
        }
    }

    private fun processTX(tx: Transaction) {
        val msgSize = tx.getMessageSize()

        uiState.useWith {
            mempoolBytes += msgSize.toLong()
        }

        state.useWith a@ {
            if (mapMemPool containsKey tx.getHash())
                return@a

            // Calculate the total output values
            val totalOutValue = tx.getOutputs().map { it.getValue().value }.sum()

            // For each input in this transaction, see if we can get the value from the output of a known transaction
            // in mapMemPool. If not, register the input as awaiting and give it a fee of -1.
            val inputs = tx.getInputs()
            val inputValues = LongArray(inputs.size())
            for (i in inputs.indices) {
                val input = inputs[i]
                val op = input.getOutpoint()
                val connectIndex = op.getIndex()
                val v = mapMemPool[tx.getHash()]?.getOutput(connectIndex)?.getValue()?.value ?: -1
                inputValues[i] = v
            }

            // inputValues is now an array of known values or -1 for unknown/not seen yet.
            // If there are no -1 values, we can calculate the fee immediately.
            if (!inputValues.contains(-1)) {
                val totalInValue = inputValues.sum()
                uiState.useWith {
                    val entry = MemPoolEntry(
                            fee = totalInValue - totalOutValue,
                            msgSize = msgSize,
                            msgSizeForPriority = tx.getMessageSizeForPriorityCalc(),
                            hash = tx.getHash(),
                            priority = 0
                    )
                    mempool add entry
                    mapEntries[tx.getHash()] = mempool.size() - 1
                }
            } else {
                // Otherwise, we don't have enough info yet. Register a PartialFeeData structure for later.
                val entry = MemPoolEntry(
                        fee = -1,
                        msgSize = msgSize,
                        msgSizeForPriority = tx.getMessageSizeForPriorityCalc(),
                        priority = -1,
                        hash = tx.getHash()
                )
                val uiListIndex = uiState.getWith {
                    mempool add entry
                    mapEntries[tx.getHash()] = mempool.size() - 1
                    mempool.size() - 1
                }
                val pfd = PartialFeeData(totalOutValue, inputValues, LongArray(inputValues.size()), entry, uiListIndex)
                val toLookup = arrayListOf<TransactionOutPoint>()
                for (i in inputValues.indices) {
                    if (inputValues[i] != -1L) continue
                    val op = inputs[i].getOutpoint()
                    mapAwaiting[op] = AwaitingInput(i, pfd)
                    toLookup add op
                }
                if (lookupImmediately)
                    doUTXOLookup(toLookup)
            }

            // For each output in this transaction, see if we can connect it to a waiting input and satisfy any
            // partial fee datas. If we can, see if we're done for this tx already.
            tx.getOutputs().forEach { maybeFinishWithOutput(it.getOutPointFor(), it.getValue().value) }

            mapMemPool[tx.getHash()] = tx
        }
    }

    fun maybeFinishWithOutput(op: TransactionOutPoint, value: Long, height: Long = -1L) {
        state.useWith {
            val awaiting = mapAwaiting[op] ?: return@useWith
            val pfd = awaiting.pfd

            pfd.inputValues[awaiting.index] = value
            if (height != -1L)
                pfd.inputHeights[awaiting.index] = height

            if (!pfd.inputValues.contains(-1L)) {
                // All inputs are now resolved, so we're done with this TX. Update the table.
                val totalIn = pfd.inputValues.sum()
                val fee = totalIn - pfd.totalOut
                val confs = pfd.inputHeights.map { if (it == 0L) 0 else store.getChainHead().getHeight() - it }
                val inputsPriority = pfd.inputValues.zip(confs).map { pair -> pair.first * pair.second }.sum()
                val priority = inputsPriority / pfd.forTx.msgSizeForPriority
                check(fee >= 0) { "fee = $totalIn - ${pfd.totalOut} = $fee  ::  " + pfd.inputValues.joinToString(",")}
                check(priority >= 0) { "priority=$priority" }
                mapAwaiting.remove(op)
                uiState.useWith {
                    mempool[pfd.mempoolIdx] = pfd.forTx.copy(fee = fee, priority = inputsPriority)
                }
            }
        }
    }

    fun doUTXOLookups() {
        // Do a lookup for each awaiting output.
        state.useWith {
            // Limit the size of the query to avoid hitting the send buffer size on the other side.
            while (true) {
                val chunk = mapAwaiting.entrySet().filterNot { it.value.utxoQueryDone }.take(5000)
                chunk.forEach { it.value.utxoQueryDone = true }
                val query = chunk.map { it.key }
                if (query.isEmpty())
                    break
                doUTXOLookup(query)
            }
        }
    }

    fun doUTXOLookup(query: List<TransactionOutPoint>) {
        val promise = pg.getDownloadPeer().getUTXOs(query, false).toPromise()

        promise success { results ->
            val bitset = BitSet.valueOf(results.getHitMap())
            var cursor = 0
            for (i in query.indices) {
                if (bitset.get(i)) {
                    val height = results.getHeights()[cursor]
                    maybeFinishWithOutput(query[i], results.getOutputs()[cursor].getValue().value, if (height != 0x7FFFFFFFL) height else -1)
                    cursor++
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    Application.launch(javaClass<App>(), *args)
}