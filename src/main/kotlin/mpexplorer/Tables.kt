package mpexplorer

import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleLongProperty
import javafx.beans.value.ObservableValue
import javafx.collections.ObservableList
import javafx.collections.transformation.SortedList
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.paint.Color
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.utils.BtcFormat

fun <T> wireSorted(table: TableView<T>, items: ObservableList<T>) {
    val sl = SortedList(items)
    sl.comparatorProperty() bind table.comparatorProperty()
    table.setItems(sl)
}

suppress("UNCHECKED_CAST")
fun configureTable(table: TableView<MemPoolEntry>, app: App) {
    val mbtc = BtcFormat.getMilliInstance()
    val ubtc = BtcFormat.getMicroInstance()

    setupRowClusterHighlighting(table)

    var c = 0

    val height = table.getColumns()[c++] as TableColumn<MemPoolEntry, Int>
    height.setCellValueFactory { features -> SimpleIntegerProperty(features.getValue().height) as ObservableValue<Int> }
    height.setStyle("-fx-alignment: CENTER")

    val size = table.getColumns()[c++] as TableColumn<MemPoolEntry, Int>
    size.setCellValueFactory { features -> SimpleIntegerProperty(features.getValue().msgSize) as ObservableValue<Int> }
    size.setStyle("-fx-alignment: CENTER")

    val fee = table.getColumns()[c++] as TableColumn<MemPoolEntry, Long>
    fee.setCellValueFactory { features -> SimpleLongProperty(features.getValue().fee) as ObservableValue<Long> }
    fee.setCellFactory { FeeColumn(mbtc) }
    fee.setStyle("-fx-alignment: CENTER")

    val feePerByte = table.getColumns()[c++] as TableColumn<MemPoolEntry, Long>
    feePerByte.setCellValueFactory { features -> SimpleLongProperty(features.getValue().feePerByte) as ObservableValue<Long> }
    feePerByte.setCellFactory { FeeColumn(ubtc) }
    feePerByte.setStyle("-fx-alignment: CENTER")

    val priority = table.getColumns()[c++] as TableColumn<MemPoolEntry, Long>
    priority.setCellValueFactory { features -> SimpleLongProperty(features.getValue().priority) as ObservableValue<Long> }
    priority.setCellFactory { column ->
        object : TextFieldTableCell<MemPoolEntry, Long>() {
            override fun updateItem(item: Long?, empty: Boolean) {
                super.updateItem(item, empty)
                if (empty)
                    setText("")
                else if (item!! == -1L)
                    setText("•")
                else
                    setText(item.toString())
            }
        }
    }
    priority.setStyle("-fx-alignment: CENTER")

    val cscore = table.getColumns()[c++] as TableColumn<MemPoolEntry, Int>
    cscore.setCellValueFactory { features -> SimpleIntegerProperty(features.getValue().shape.clusterScore) as ObservableValue<Int> }

    val hash = table.getColumns()[c] as TableColumn<MemPoolEntry, Sha256Hash>
    hash.setCellValueFactory(PropertyValueFactory("hash"))
    hash.setCellFactory { column ->
        object : TextFieldTableCell<MemPoolEntry, Sha256Hash>() {
            init {
                setOnMouseClicked { ev ->
                    if (ev.getClickCount() == 2)
                        app.controller.openWebPage("https://tradeblock.com/blockchain/tx/${getText()}", getText())
                }
            }
        }
    }
}

// Make row redder the more the inputs/outputs look like DoS/spam.
private fun setupRowClusterHighlighting(table: TableView<MemPoolEntry>) {
    table.setRowFactory {
        object : TableRow<MemPoolEntry>() {
            override fun updateItem(item: MemPoolEntry?, empty: Boolean) {
                super.updateItem(item, empty)
                getStyleClass().removeAll("cluster1", "cluster2", "cluster3", "cluster4", "cluster5")
                if (item != null && item.shape.clusterScore > 0) {
                    var x = item.shape.clusterScore
                    val i = 40
                    var cluster = 0
                    while (x > i) {
                        x -= i
                        if (cluster <= 5)
                            cluster++
                    }
                    getStyleClass().add(0, "cluster$cluster")
                }
            }
        }
    }
}

