package mpexplorer

import com.google.common.io.BaseEncoding
import humanize.Humanize
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.binding.Bindings.createStringBinding
import javafx.beans.binding.Bindings.format
import javafx.beans.binding.Bindings.size
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleLongProperty
import javafx.collections.FXCollections
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.web.WebView
import javafx.stage.FileChooser
import javafx.stage.Stage
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.async
import nl.komponents.kovenant.functional.unwrap
import org.bitcoinj.core.*
import org.bitcoinj.core.listeners.*
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.Script
import org.bitcoinj.store.MemoryBlockStore
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.utils.Threading
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Arrays
import java.util.BitSet
import java.util.Timer
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import kotlin.concurrent.scheduleAtFixedRate

// TODO: Show histogram of cluster score selections. We're looking to maximize selection of non-spammy txns

val params = MainNetParams.get()

/** Records statistics used for cluster calculation. May be prepared before inputs are ready and then updated later. */
data class TxShapeStats(
        val nInputs: Int, val nOutputs: Int,
        val identicalInputValues: Int, val identicalOutputValues: Int,
        val identicalOutputScripts: Int,
        val highestFrequencyOutputValue: Coin?,
        val highestFrequencyOutputScript: Script?
) {
    fun withInputs(inputValues_: LongArray): TxShapeStats {
        val inputValues = inputValues_.filterNot { it == -1L }.toLongArray()
        if (inputValues.isEmpty())
            return this
        inputValues.sort()
        val inputReps = calcRepetitions(inputValues)
        return copy(identicalInputValues = inputReps.distinct().map { if (it == 1) 0 else it }.sum())
    }

    // Use a brain dead cluster score for now.
    val clusterScore: Int
        get() = identicalInputValues + identicalOutputScripts + identicalOutputValues

    override fun toString(): String =
            "$nInputs inputs, $nOutputs outputs" +
            if (identicalInputValues > 0) ", $identicalInputValues identical input values" else "" +
            if (identicalOutputValues > 0) ", $identicalOutputValues identical output values" else "" +
            if (identicalOutputScripts > 0) ", $identicalOutputScripts identical output scripts" else "" +
            if (highestFrequencyOutputScript != null) ", top address ${highestFrequencyOutputScript.getToAddress(params)}" else ""
}

/** Extension function that generates shape stats from the data held only in the transaction itself */
fun Transaction.calcShape(): TxShapeStats {
    // How many times do the same values appear?
    check(outputs.isNotEmpty())
    check(inputs.isNotEmpty())
    val outputValues = outputs.map { it.value }.sorted().map { it.value }.toLongArray()
    val outputValueReps = calcRepetitions(outputValues)

    val outputScripts = outputs.map { Arrays.hashCode(it.scriptPubKey.program).toLong() }.toLongArray()
    val outputScriptReps = calcRepetitions(outputScripts)

    val allOutputsDifferent = outputValueReps.count { it == 1 } == outputValueReps.size()
    val highestFreqOutputVal = if (allOutputsDifferent) null else Coin.valueOf(outputValues[outputValueReps.indexOf(outputValueReps.max())])
    val highestFreqOutputScript = if (outputScriptReps.isEmpty()) null else getOutput(outputScriptReps.indexOf(outputScriptReps.max()).toLong()).scriptPubKey

    return TxShapeStats(
            nInputs = inputs.size(),
            nOutputs = outputs.size(),
            identicalOutputValues = outputValueReps.distinct().map { if (it == 1) 0 else it }.sum(),
            identicalOutputScripts = outputScriptReps.distinct().map { if (it == 1) 0 else it }.sum(),
            highestFrequencyOutputValue = highestFreqOutputVal,
            highestFrequencyOutputScript = highestFreqOutputScript,
            identicalInputValues = 0
    )
}

/** Given a sorted list, figures out how many times the values repeat, e.g. [1, 1, 2, 3, 3, 3] -> [2, 2, 1, 3, 3, 3] */
fun calcRepetitions(values: LongArray): List<Int> {
    val counts = values.groupBy { it }
    return values.map { counts[it]!!.size() }
}

/**
 * Holds entry an entry in the memory pool derived from tx data and fetched inputs.
 * Doesn't store the whole transaction as we don't need it all.
 */
data class MemPoolEntry(
        val fee: Long,
        val msgSize: Int,
        val msgSizeForPriority: Int,
        val priority: Long,
        val hash: Sha256Hash,
        val height: Int = 0,
        val shape: TxShapeStats
) {
    val feePerByte: Long get() = if (fee == -1L) -1L else fee / msgSize
}

/** Class that manages the UI. Fields marked with @FXML are injected from the UI layout language. */
class UIController {
    @FXML lateinit var mempoolTable: TableView<MemPoolEntry> 
    @FXML lateinit var blockMakerTable: TableView<MemPoolEntry> 
    @FXML lateinit var numTxnsLabel: Label 
    @FXML lateinit var numTxnsInLastBlockLabel: Label 
    @FXML lateinit var mempoolBytesLabel: Label 

    @FXML lateinit var blockSizeSlider: Slider 
    @FXML lateinit var priorityAreaSizeSlider: Slider 
    @FXML lateinit var blockSizeLabel: Label 
    @FXML lateinit var priorityAreaSizeLabel: Label 

    @FXML lateinit var txShapeLabel1: Label 
    @FXML lateinit var txShapeLabel2: Label 
    @FXML lateinit var txPerSecLabel: Label 
    @FXML lateinit var tabPane: TabPane 

    lateinit var blockMaker: BlockMaker
    lateinit var stage: Stage

    @Suppress("UNCHECKED_CAST")
    public fun init(app: App) {
        stage = app.stage
        configureTable(mempoolTable, app)
        configureTable(blockMakerTable, app)

        app.uiState.useWith {
            blockMaker = BlockMaker(mempool)
            wireSorted(mempoolTable, mempool)
            wireSorted(blockMakerTable, blockMaker.contents)

            numTxnsLabel.textProperty() bind format(numTxnsLabel.text, size(mempool))
            numTxnsInLastBlockLabel.textProperty() bind format(numTxnsInLastBlockLabel.text, numTxnsInLastBlock)

            mempoolBytesLabel.textProperty() bind format(mempoolBytesLabel.text,
                createStringBinding(Callable { Humanize.binaryPrefix(mempoolBytes.get()) }, mempoolBytes)
            )

            txPerSecLabel.textProperty() bind createStringBinding(Callable {
                "%.1f transactions per second".format(txnsPerSec.get())
            }, txnsPerSec)
        }

        // Wire up the block maker sliders:
        // - Priority area cannot be bigger than the block size itself
        priorityAreaSizeSlider.maxProperty() bind blockSizeSlider.valueProperty()
        // - Block size label shows size in humanized bytes
        blockSizeLabel.textProperty() bind createStringBinding(Callable {
            Humanize.binaryPrefix(blockSizeSlider.value.toInt())
        }, blockSizeSlider.valueProperty())
        // - Priority area size label shows size in humanized bytes
        priorityAreaSizeLabel.textProperty() bind createStringBinding(Callable {
            Humanize.binaryPrefix(priorityAreaSizeSlider.value.toInt())
        }, priorityAreaSizeSlider.valueProperty())
        // - Editing either slider results in a recalculation of the block
        blockSizeSlider.valueProperty().addListener { o -> update() }
        priorityAreaSizeSlider.valueProperty().addListener { o -> update() }

        blockSizeSlider.max = 8 * 1000 * 1024.0   // 8mb blocks

        fun wireShapeLabel(label: Label, table: TableView<MemPoolEntry>) {
            table.selectionModel.selectedItemProperty().addListener { value, old, new ->
                if (new != null)
                    label.text = new.shape.toString()
                else
                    label.text = ""
            }
        }
        wireShapeLabel(txShapeLabel1, mempoolTable)
        wireShapeLabel(txShapeLabel2, blockMakerTable)
    }

    @FXML
    public fun onCalcPressed(ev: ActionEvent) {
        update()
    }

    private fun update() {
        blockMaker.calculate(blockSizeSlider.value.toInt(), priorityAreaSizeSlider.value.toInt())
    }

    fun openWebPage(url: String, title: String) {
        val tab = Tab(title)
        val webView = WebView()
        webView.engine.load(url)
        tab.content = webView
        tabPane.tabs.add(tab)
    }

    @FXML
    fun onLoadBlock(ev: ActionEvent) {
        val chooser = FileChooser()
        chooser.title = "Select block file"
        val file = chooser.showOpenDialog(stage) ?: return
        var bytes = file.readBytes()
        try {
            bytes = BaseEncoding.base16().decode(bytes.toString(Charsets.UTF_8).toUpperCase().trim())
        } catch (e: IllegalArgumentException) {
            // Ignore, assume the file contains raw block bytes instead of a hex string.
        }
        try {
            val block = BitcoinSerializer(params, false).makeBlock(bytes)
            println(block)
        } catch(e: ProtocolException) {
            Alert(Alert.AlertType.ERROR, "Could not parse as block contents: ensure the file is raw bytes or hex").showAndWait()
        }
    }

}

/** Main app logic for downloading txns and resolving the inputs using getutxo */
class App : Application() {
    val log = LoggerFactory.getLogger(App::class.java)

    val bitcoinj = Context(params)
    lateinit var controller: UIController
    lateinit var store: MemoryBlockStore
    lateinit var pg: PeerGroup

    val uiState = UIThreadBox(object {
        val mempool = FXCollections.observableArrayList<MemPoolEntry>()
        var mempoolBytes = SimpleLongProperty()
        val numTxnsInLastBlock = SimpleIntegerProperty()
        val txnsPerSec = SimpleDoubleProperty()

        // Simple moving average buffer.
        var txnsPerSecBuffer = IntArray(4)
        var txnsPerSecBufCursor = 0
        var txnsSinceLastTick = 0
    })

    // Input values we haven't found yet. When every entry in inputValues is found, we can calculate the
    // fee and insert it into the mempool list.
    data class PartialFeeData(
            val totalOut: Long,
            val inputValues: LongArray,    // one entry per input, -1 if still unknown
            val inputHeights: LongArray,   // one entry per input, 0 if unknown
            val forTx: MemPoolEntry,
            val mempoolIdx: Int
    )
    data class AwaitingInput(val index: Int, val pfd: PartialFeeData, var utxoQueryDone: Boolean = false)

    // Put in a box just in case we want to shift calculations off the UI thread.
    val state = ThreadBox(object {
        val mapMemPool = hashMapOf<Sha256Hash, Transaction>()
        val mapEntries = hashMapOf<Sha256Hash, Int>()  // index in mempool array
        val mapAwaiting = hashMapOf<TransactionOutPoint, AwaitingInput>()
        var initialDownloadDone = false
    })

    lateinit var stage: Stage

    override fun start(stage: Stage) {
        BriefLogFormatter.init()

//        Logger.getLogger("").getHandlers()[0].setLevel(Level.ALL)
//        Logger.getLogger("").setLevel(Level.OFF)
//        Logger.getLogger("mpexplorer").setLevel(Level.ALL)
//        Logger.getLogger("org.bitcoinj.core").setLevel(Level.ALL)

        this.stage = stage
        val loader = FXMLLoader(App::class.java.getResource("main.fxml"))
        val scene = Scene(loader.load())
        scene.stylesheets.add(App::class.java.getResource("main.css").toString())
        controller = loader.getController()
        controller.init(this)
        stage.scene = scene
        stage.title = "Mempool Explorer"
        stage.isMaximized = true
        stage.show()

        // Make callbacks run on the UI thread by default.
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
            Context.propagate(bitcoinj)
            val params = bitcoinj.params
            val now = Instant.now().epochSecond
            store = MemoryBlockStore(params)
            CheckpointManager.checkpoint(params, CheckpointManager.openStream(params), store, now)

            val blockchain = BlockChain(bitcoinj, store)
            pg = PeerGroup(bitcoinj, blockchain)

            // Ensure that when we see transactions in a block, we update our tracking. Entries stay in our "mempool"
            // structures forever but with an updated height, so we can easily exclude them when we want.
            val listener = object : TransactionReceivedInBlockListener, NewBestBlockListener {
                var sizeOfLastBlock: Long = 0L
                var numTxns: Int = 0

                override fun receiveFromBlock(tx: Transaction, block: StoredBlock, blockType: AbstractBlockChain.NewBlockType, relativityOffset: Int) {
                    if (tx.isCoinBase) return
                    if (blockType != AbstractBlockChain.NewBlockType.BEST_CHAIN) return
                    sizeOfLastBlock += tx.messageSize
                    numTxns++
                    val height = block.height
                    uiState.useWith {
                        state.useWith {
                            val index: Int? = mapEntries[tx.hash]
                            if (index != null)
                                mempool[index] = mempool[index].copy(height = height)
                        }
                    }
                }

                override fun notifyTransactionIsInBlock(txHash: Sha256Hash, block: StoredBlock, blockType: AbstractBlockChain.NewBlockType, relativityOffset: Int): Boolean {
                    // Don't care.
                    return false
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
            }
            blockchain.addTransactionReceivedListener(listener)
            blockchain.addNewBestBlockListener(listener)

            // Force connection only to XT nodes that support getutxos. This will make bitcoinj use HTTP/Cartographer
            // seeds automatically.
            pg.setRequiredServices(GetUTXOsMessage.SERVICE_FLAGS_REQUIRED)
            pg.useLocalhostPeerWhenPossible = false
            pg.setDownloadTxDependencies(false)
            pg.maxConnections = 1
            pg.fastCatchupTimeSecs = now
            pg.setUserAgent("Mempool Explorer", "1.0")
            pg.start()
            val tracker = DownloadProgressTracker()
            pg.startBlockChainDownload(tracker)
            tracker.await()
            pg.waitForPeers(1).toPromise()
            // Free up the thread we're on here ....
        }.unwrap() success {
            // And we're back.
            val peer = it[0]

            // Respond to network events:
            peer.addOnTransactionBroadcastListener { peer, tx ->
                processTX(tx)
            }
            peer.sendMessage(MemoryPoolMessage())
            peer.ping().toPromise() success {
                doInitialUTXOLookups()
            }

            setupTxPerSecCounter()
        }
    }

    private fun setupTxPerSecCounter() {
        // Update the transactions per second counter.
        Timer(true).scheduleAtFixedRate(2000, 1000) {
            Platform.runLater {
                uiState.useWith {
                    txnsPerSecBuffer[txnsPerSecBufCursor++ mod txnsPerSecBuffer.size()] = txnsSinceLastTick
                    txnsSinceLastTick = 0
                    txnsPerSec.set(txnsPerSecBuffer.average())
                }
            }
        }
    }

    private fun processTX(tx: Transaction) {
        val msgSize = tx.messageSize

        uiState.useWith {
            mempoolBytes += msgSize.toLong()
            txnsSinceLastTick++
        }

        state.useWith a@ {
            if (mapMemPool containsKey tx.hash)
                return@a

            mapMemPool[tx.hash] = tx

            // Calculate the total output values
            val totalOutValue = tx.outputs.map { it.value.value }.sum()

            // For each input in this transaction, see if we can get the value from the output of a known transaction
            // in mapMemPool. If not, register the input as awaiting and give it a fee of -1.
            val inputs = tx.inputs
            val inputValues = LongArray(inputs.size())
            for (i in inputs.indices) {
                val input = inputs[i]
                val op = input.outpoint
                val connectIndex = op.index
                val v = mapMemPool[op.hash]?.getOutput(connectIndex)?.value?.value ?: -1
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
                            msgSizeForPriority = tx.messageSizeForPriorityCalc,
                            hash = tx.hash,
                            priority = 0,
                            shape = tx.calcShape().withInputs(inputValues)
                    )
                    mempool add entry
                    mapEntries[tx.hash] = mempool.size() - 1
                }
            } else {
                // Otherwise, we don't have enough info yet. Register a PartialFeeData structure for later.
                val entry = MemPoolEntry(
                        fee = -1,
                        msgSize = msgSize,
                        msgSizeForPriority = tx.messageSizeForPriorityCalc,
                        priority = -1,
                        hash = tx.hash,
                        shape = tx.calcShape()   // input data will be filled in later
                )
                val uiListIndex = uiState.getWith {
                    mempool add entry
                    mapEntries[tx.hash] = mempool.size() - 1
                    mempool.size() - 1
                }
                val pfd = PartialFeeData(totalOutValue, inputValues, LongArray(inputValues.size()), entry, uiListIndex)
                val toLookup = arrayListOf<TransactionOutPoint>()
                for (i in inputValues.indices) {
                    if (inputValues[i] != -1L) continue
                    val op = inputs[i].outpoint
                    mapAwaiting[op] = AwaitingInput(i, pfd)
                    toLookup add op
                }
                if (initialDownloadDone)
                    doUTXOLookup(toLookup)
            }

            // For each output in this transaction, see if we can connect it to a waiting input and satisfy any
            // partial fee datas. If we can, see if we're done for this tx already.
            tx.outputs.forEach { maybeFinishWithOutput(it.outPointFor, it.value.value) }
        }
    }

    fun maybeFinishWithOutput(op: TransactionOutPoint, value: Long, height: Long = -1L) {
        state.useWith {
            val awaiting: AwaitingInput = mapAwaiting[op] ?: return@useWith
            val pfd: PartialFeeData = awaiting.pfd

            pfd.inputValues[awaiting.index] = value
            if (height != -1L)
                pfd.inputHeights[awaiting.index] = height

            check(pfd.inputValues.isNotEmpty())
            if (!pfd.inputValues.contains(-1L)) {
                // All inputs are now resolved, so we're done with this TX. Update the table.
                val totalIn = pfd.inputValues.sum()
                val fee = totalIn - pfd.totalOut
                val confs = pfd.inputHeights.map { if (it == 0L) 0 else store.chainHead.height - it }
                val inputsPriority = pfd.inputValues.zip(confs).map { pair -> pair.first * pair.second }.sum()
                val priority = inputsPriority / pfd.forTx.msgSizeForPriority
                check(fee >= 0) { "fee = $totalIn - ${pfd.totalOut} = $fee  ::  " + pfd.inputValues.joinToString(",")}
                check(priority >= 0) { "priority=$priority: $inputsPriority" }
                mapAwaiting.remove(op)
                uiState.useWith {
                    mempool[pfd.mempoolIdx] = pfd.forTx.copy(fee = fee, priority = inputsPriority, shape = pfd.forTx.shape.withInputs(pfd.inputValues))
                }
            }
        }
    }

    fun doInitialUTXOLookups() {
        // Do a lookup for each awaiting output.
        state.useWith {
            initialDownloadDone = true
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
        // false here means: don't query utxos created only in the mempool. Otherwise, we'd not be able to query any
        // block chain outputs that were spent by the mempool. However, this means we MUST have full visibility into
        // every tx that is broadcast. Otherwise stuff won't show up.
        val promise = pg.downloadPeer.getUTXOs(query, false).toPromise()

        promise success { results ->
            val bitset = BitSet.valueOf(results.hitMap)
            var cursor = 0
            for (i in query.indices) {
                if (bitset.get(i)) {
                    val height = results.heights[cursor]
                    maybeFinishWithOutput(query[i], results.outputs[cursor].value.value, if (height != 0x7FFFFFFFL) height else -1)
                    cursor++
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    Application.launch(App::class.java, *args)
}