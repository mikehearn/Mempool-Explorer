package mpexplorer

import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleLongProperty
import javafx.beans.value.ObservableValue
import javafx.collections.ObservableList
import javafx.collections.transformation.SortedList
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.control.cell.TextFieldTableCell
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.utils.BtcFormat

fun <T> wireSorted(table: TableView<T>, items: ObservableList<T>) {
    val sl = SortedList(items)
    sl.comparatorProperty() bind table.comparatorProperty()
    table.setItems(sl)
}

suppress("UNCHECKED_CAST")
fun configureTable(table: TableView<MemPoolEntry>) {
    val mbtc = BtcFormat.getMilliInstance()
    val ubtc = BtcFormat.getMicroInstance()

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

    val hash = table.getColumns()[c] as TableColumn<MemPoolEntry, Sha256Hash>
    hash.setCellValueFactory(PropertyValueFactory("hash"))
}

