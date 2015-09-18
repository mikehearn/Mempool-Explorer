package mpexplorer

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.ScriptBuilder
import org.junit.Test
import kotlin.test.assertEquals

class Tests {
    @Test fun reps() {
        assertEquals("3,3,3,1,2,2", calcRepetitions(longArrayOf(1, 1, 1, 2, 3, 3)).joinToString(","))
        assertEquals("1,1,1", calcRepetitions(longArrayOf(1, 2, 3)).joinToString(","))
    }

    @Test fun txShapes() {
        val params = MainNetParams.get()
        val t = Transaction(params)
        val addr = Address.fromBase58(params, "12eqqejKyg5foH9FMnLQkWL7NnkTpXbMsp")
        t.addOutput(Coin.COIN, addr)
        t.addOutput(Coin.COIN, addr)
        t.addOutput(Coin.FIFTY_COINS, addr)
        t.addInput(Sha256Hash.wrap("5f5e506945b929999e8318d3be3279f7cb4e58f54b6b474628dc2bf6974cdc5f"), 1, ScriptBuilder.createInputScript(TransactionSignature.dummy()))
        val shape = t.calcShape()
        assertEquals(3, shape.identicalOutputScripts)
        assertEquals(2, shape.identicalOutputValues)
        assertEquals(t.getOutput(0).scriptPubKey, shape.highestFrequencyOutputScript)
    }
}