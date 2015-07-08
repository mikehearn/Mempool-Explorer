package mpexplorer

import humanize.Humanize
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.binding.Bindings.createStringBinding
import javafx.beans.binding.Bindings.format
import javafx.beans.binding.Bindings.size
import javafx.beans.binding.StringBinding
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleLongProperty
import javafx.collections.FXCollections
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.scene.chart.StackedAreaChart
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.control.cell.TextFieldTableCell
import javafx.stage.Stage
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.async
import org.bitcoinj.core.*
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.store.MemoryBlockStore
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.utils.BtcFormat
import org.bitcoinj.utils.DaemonThreadFactory
import org.bitcoinj.utils.Threading
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.BitSet
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

data class MemPoolEntry(val fee: Long, val msgSize: Int, val hash: Sha256Hash) {
    val feePerByte: Long get() = if (fee == -1L) -1L else fee / msgSize
}

class UIController {
    val log = LoggerFactory.getLogger(javaClass<UIController>())

    FXML public var table: TableView<MemPoolEntry> = later()
    FXML public var numTxnsLabel: Label = later()
    FXML public var numTxnsInLastBlockLabel: Label = later()
    FXML public var mempoolBytesLabel: Label = later()
    FXML public var mempoolGraph: StackedAreaChart<Number, Number> = later()

    suppress("UNCHECKED_CAST")
    public fun init(app: App) {
        val btcFormatter = BtcFormat.getInstance()

        val col1 = table.getColumns()[0] as TableColumn<MemPoolEntry, String>
        col1.setCellValueFactory { features ->
            object : StringBinding() {
                override fun computeValue(): String {
                    val fee = features.getValue().fee
                    return if (fee == -1L) "•" else btcFormatter.format(Coin.valueOf(fee))
                }
            }
        }
        col1.setStyle("-fx-alignment: CENTER")

        val col2 = table.getColumns()[1] as TableColumn<MemPoolEntry, String>
        col2.setCellValueFactory { features ->
            object : StringBinding() {
                override fun computeValue(): String {
                    val fee = features.getValue().feePerByte
                    return if (fee == -1L) "•" else btcFormatter.format(Coin.valueOf(fee))
                }
            }
        }
        col2.setStyle("-fx-alignment: CENTER")

        val col3 = table.getColumns()[2] as TableColumn<MemPoolEntry, Sha256Hash>
        col3.setCellValueFactory(PropertyValueFactory("hash"))
        col3.setCellFactory { column ->
            object : TextFieldTableCell<MemPoolEntry, Sha256Hash>() {
                init {
                    setOnMouseClicked { ev ->
                        app.state.useWith {
                            val hash = getItem()
                            log.info("TX ${hash}:")
                            val tx = mapMemPool[hash]
                            for (i in tx.getInputs().indices) {
                                val inp = tx.getInputs()[i]
                                log.info("   $i: ${inp.getOutpoint()}")
                                val aw = mapAwaiting[inp.getOutpoint()] ?: continue
                                log.info("       $aw")
                            }
                        }
                        if (ev.getClickCount() == 2)
                            app.getHostServices().showDocument("https://blockchain.info/tx/${getText()}")
                    }
                }
            }
        }

        app.uiState.useWith {
            numTxnsLabel.textProperty() bind format(numTxnsLabel.getText(), size(mempool))
            numTxnsInLastBlockLabel.textProperty() bind format(numTxnsInLastBlockLabel.getText(), numTxnsInLastBlock)

            mempoolBytesLabel.textProperty() bind format(mempoolBytesLabel.getText(),
                createStringBinding(Callable { Humanize.binaryPrefix(mempoolBytes.get()) }, mempoolBytes)
            )
        }
    }
}

class App : Application() {
    val log = LoggerFactory.getLogger(javaClass<App>())

    var controller: UIController by Delegates.notNull()
    val ctx = Context(MainNetParams.get())
    var pg: PeerGroup by Delegates.notNull()

    val uiState = UIThreadBox(object {
        val mempool = FXCollections.observableArrayList<MemPoolEntry>()
        var mempoolBytes = SimpleLongProperty()
        val numTxnsInLastBlock = SimpleIntegerProperty()
    })

    // Input values we haven't found yet. When every entry in inputValues is found, we can calculate the
    // fee and insert it into the mempool list.
    data class PartialFeeData(val totalOut: Long, val inputValues: LongArray, val forTx: MemPoolEntry, val mempoolIdx: Int)
    data class AwaitingInput(val index: Int, val pfd: PartialFeeData, var utxoQueryDone: Boolean = false)

    // Put in a box just in case we want to shift calculations off the UI thread.
    val state = ThreadBox(object {
        val mapMemPool = hashMapOf<Sha256Hash, Transaction>()
        val mapAwaiting = hashMapOf<TransactionOutPoint, AwaitingInput>()
    })

    val scheduler = ScheduledThreadPoolExecutor(1, DaemonThreadFactory("utxo lookup thread"))

    override fun start(stage: Stage) {
        BriefLogFormatter.init()

        val loader = FXMLLoader(javaClass<App>().getResource("main.fxml"))
        val scene = Scene(loader.load())
        controller = loader.getController()
        uiState.useWith {
            controller.table.setItems(mempool)
        }
        controller.init(this)
        stage.setScene(scene)
        stage.setTitle("Mempool Explorer")
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
            val store = MemoryBlockStore(params)
            CheckpointManager.checkpoint(params, CheckpointManager.openStream(params), store, now)

            val blockchain = BlockChain(ctx, store)
            pg = PeerGroup(ctx, blockchain)

            //pg.addAddress(InetAddress.getByName("plan99.net"))

            pg.setFastCatchupTimeSecs(now)
            pg.setUserAgent("Mempool Explorer", "1.0")
            pg.start()
            val tracker = DownloadProgressTracker()
            pg.startBlockChainDownload(tracker)
            tracker.await()
            pg.waitForPeers(1).toPromise()
        }.unwrap() success {
            val peer = it[0]

            // After waiting 5 seconds to give us time to fetch the mempool and fully resolve from that,
            // kick off UTXO lookups every three seconds, if needed (to avoid putting excessive work on
            // the remote peer).
            scheduler.scheduleWithFixedDelay(Runnable {
                doUTXOLookups()
            }, 15, 15, TimeUnit.SECONDS)

            // Respond to network events:
            peer.addEventListener(object : AbstractPeerEventListener() {
                override fun onTransaction(peer: Peer, t: Transaction) {
                    processTX(t)
                }

                override fun onPeerDisconnected(p: Peer, peerCount: Int) {
                    //if (peer == p) {
                     //   p.removeEventListener(this)
                     //   pg.waitForPeers(1).get()[0].addEventListener(this)
                   // }
                }

                override fun onBlocksDownloaded(peer: Peer, block: Block?, filteredBlock: FilteredBlock?, blocksLeft: Int) {
                    // TODO: Move this to an in-memory block store handler so we ignore orphan blocks and do reorgs properly.
                    val txns = block!!.getTransactions().toSet()
                    val size = block.getMessageSize() - Block.HEADER_SIZE - VarInt.sizeOf(txns.size().toLong())
                    state.useWith {
                        txns map { it.getHash() } forEach { mapMemPool.remove(it) }
                    }
                    uiState.useWith {
                        mempool.removeAll(txns)
                        mempoolBytes -= size.toLong()
                        numTxnsInLastBlock.set(txns.size())
                    }
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
                    mempool add MemPoolEntry(totalInValue - totalOutValue, msgSize, tx.getHash())
                }
            } else {
                // Otherwise, we don't have enough info yet. Register a PartialFeeData structure for later.
                val entry = MemPoolEntry(-1, msgSize, tx.getHash())
                val uiListIndex = uiState.getWith {
                    mempool add entry
                    mempool.size() - 1
                }
                val pfd = PartialFeeData(totalOutValue, inputValues, entry, uiListIndex)
                for (i in inputValues.indices) {
                    if (inputValues[i] != -1L) continue
                    mapAwaiting[inputs[i].getOutpoint()] = AwaitingInput(i, pfd)
                }
            }

            // For each output in this transaction, see if we can connect it to a waiting input and satisfy any
            // partial fee datas. If we can, see if we're done for this tx already.
            tx.getOutputs().forEach { maybeFinishWithOutput(it.getOutPointFor(), it.getValue().value) }

            mapMemPool[tx.getHash()] = tx
        }
    }

    fun maybeFinishWithOutput(op: TransactionOutPoint, value: Long) {
        state.useWith {
            val awaiting = mapAwaiting[op] ?: return@useWith
            awaiting.pfd.inputValues[awaiting.index] = value
            if (!awaiting.pfd.inputValues.contains(-1L)) {
                // All inputs are now resolved, so we're done with this TX. Update the table.
                val totalIn = awaiting.pfd.inputValues.sum()
                val fee = totalIn - awaiting.pfd.totalOut
                check(fee >= 0) { "fee = $totalIn - ${awaiting.pfd.totalOut} = $fee  ::  " + awaiting.pfd.inputValues.joinToString(",")}
                mapAwaiting.remove(op)
                uiState.useWith {
                    mempool[awaiting.pfd.mempoolIdx] = awaiting.pfd.forTx.copy(fee = fee)
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

                val promise = pg.getDownloadPeer().getUTXOs(query, false).toPromise()

                promise success { results ->
                    val bitset = BitSet.valueOf(results.getHitMap())
                    log.info("Found ${results.getOutputs().size()} UTXOs from query of ${query.size()} outpoints")
                    var cursor = 0
                    for (i in query.indices) {
                        if (bitset.get(i)) {
                            maybeFinishWithOutput(query[i], results.getOutputs()[cursor].getValue().value)
                            cursor++
                        }
                    }
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    Application.launch(javaClass<App>(), *args)
}