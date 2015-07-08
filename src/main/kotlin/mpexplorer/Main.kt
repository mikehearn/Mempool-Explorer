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
import javafx.stage.Stage
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.async
import org.bitcoinj.core.*
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.utils.BtcFormat
import org.bitcoinj.utils.Threading
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import kotlin.properties.Delegates

data class TxnTableEntry(val fee: Long, val feePerByte: Long, val hash: Sha256Hash)

class UIController {
    FXML public var table: TableView<TxnTableEntry> = later()
    FXML public var numTxnsLabel: Label = later()
    FXML public var numTxnsInLastBlockLabel: Label = later()
    FXML public var mempoolBytesLabel: Label = later()
    FXML public var mempoolGraph: StackedAreaChart<Number, Number> = later()

    public fun init(app: App) {
        val btcFormatter = BtcFormat.getInstance()

        val col1 = table.getColumns()[0] as TableColumn<TxnTableEntry, String>
        //col1.setCellValueFactory(PropertyValueFactory<TxnTableEntry, Long>("fee"))
        col1.setCellValueFactory { features ->
            object : StringBinding() {
                override fun computeValue(): String = btcFormatter.format(Coin.valueOf(features.getValue().fee))
            }
        }

        val col2 = table.getColumns()[1] as TableColumn<TxnTableEntry, Long>
        col2.setCellValueFactory(PropertyValueFactory("feePerByte"))

        val col3 = table.getColumns()[2] as TableColumn<TxnTableEntry, Sha256Hash>
        col3.setCellValueFactory(PropertyValueFactory("hash"))

        numTxnsLabel.textProperty() bind format(numTxnsLabel.getText(), size(app.mempool))
        numTxnsInLastBlockLabel.textProperty() bind format(numTxnsInLastBlockLabel.getText(), app.numTxnsInLastBlock)

        mempoolBytesLabel.textProperty() bind format(mempoolBytesLabel.getText(),
                createStringBinding(Callable { Humanize.binaryPrefix(app.mempoolBytes.get()) }, app.mempoolBytes)
        )
    }
}

class App : Application() {
    var controller: UIController by Delegates.notNull()
    val bitcoinContext = Context(MainNetParams.get())

    val mempool = FXCollections.observableArrayList<TxnTableEntry>()
    val mempoolHashes = hashSetOf<Sha256Hash>()
    var mempoolBytes = SimpleLongProperty()
    val numTxnsInLastBlock = SimpleIntegerProperty()

    override fun start(stage: Stage) {
        BriefLogFormatter.init()

        val loader = FXMLLoader(javaClass<App>().getResource("main.fxml"))
        val scene = Scene(loader.load())
        controller = loader.getController()
        controller.table.setItems(mempool)
        controller.init(this)
        stage.setScene(scene)
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
            Context.propagate(bitcoinContext)

            val pg = PeerGroup(bitcoinContext)
            pg.addAddress(InetAddress.getByName("plan99.net"))
            pg.start()
            pg.waitForPeers(1).toPromise()
        }.unwrap() success {
            val peer = it[0]
            peer.addEventListener(object : AbstractPeerEventListener() {
                override fun onTransaction(peer: Peer, t: Transaction) {
                    if (mempoolHashes contains t.getHash())
                        return

                    val utxos = t.getInputs().map { it.getOutpoint() }
                    // Note: values are unauthenticated. We could run the scripts.
                    val promise = peer.getUTXOs(utxos, false).toPromise()

                    promise success {
                        val totalInputs = it.getOutputs().map { it.getValue().value }.sum()
                        val totalOutputs = t.getOutputs().map { it.getValue().value }.sum()
                        val fee = totalOutputs - totalInputs
                        val msgSize = t.getMessageSize()
                        mempoolBytes += msgSize.toLong()
                        mempool add TxnTableEntry(fee, fee / msgSize, t.getHash())
                    }
                }

                override fun onBlocksDownloaded(peer: Peer, block: Block?, filteredBlock: FilteredBlock?, blocksLeft: Int) {
                    // TODO: Move this to an in-memory block store handler so we ignore orphan blocks and do reorgs properly.
                    val txns = block!!.getTransactions().toSet()
                    val size = block.getMessageSize() - Block.HEADER_SIZE - VarInt.sizeOf(txns.size().toLong())
                    mempool.removeAll(txns)
                    mempoolHashes.removeAll(txns.map { it.getHash() })
                    mempoolBytes -= size.toLong()
                    numTxnsInLastBlock.set(txns.size())
                }
            })
            peer.sendMessage(MemoryPoolMessage())
        }
    }
}

fun main(args: Array<String>) {
    Application.launch(javaClass<App>(), *args)
}