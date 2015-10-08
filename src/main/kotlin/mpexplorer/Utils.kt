package mpexplorer

import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import javafx.application.Platform
import javafx.beans.property.SimpleLongProperty
import nl.komponents.kovenant.Dispatcher
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.bitcoinj.core.Coin
import java.util.concurrent.CompletableFuture

class JFXDispatcher : Dispatcher {
    override val stopped: Boolean get() = throw UnsupportedOperationException()
    override val terminated: Boolean get() = throw UnsupportedOperationException()

    override fun offer(task: () -> Unit): Boolean {
        Platform.runLater(task)
        return true
    }

    override fun stop(force: Boolean, timeOutMs: Long, block: Boolean): List<() -> Unit> = throw UnsupportedOperationException()
    override fun tryCancel(task: () -> Unit): Boolean = throw UnsupportedOperationException()
}

class ThreadBox<T>(private val data: T) {
    @Synchronized fun use<R>(block: (T) -> R): R = block(data)
    @Synchronized fun useWith<R>(block: T.() -> R): R = data.block()
}

class UIThreadBox<T>(private val data: T) {
    fun use(block: (T) -> Unit): Unit = if (Platform.isFxApplicationThread()) block(data) else Platform.runLater { block(data) }
    fun useWith(block: T.() -> Unit): Unit = if (Platform.isFxApplicationThread()) data.block() else Platform.runLater { data.block() }

    /** Does a blocking get from the UI thread - danger of deadlock if not used properly! */
    fun getWith<R>(block: T.() -> R): R {
        if (Platform.isFxApplicationThread())
            return data.block()
        val f = CompletableFuture<R>()
        Platform.runLater {
            try {
                f.complete(data.block())
            } catch (e: Throwable) {
                f.completeExceptionally(e)
            }
        }
        return f.get()
    }
}

fun <T : Any> ListenableFuture<T>.toPromise(): Promise<T, Exception> {
    val def = deferred<T, Exception>()
    Futures.addCallback(this, object : FutureCallback<T> {
        override fun onFailure(t: Throwable) = def.reject(t as Exception)
        override fun onSuccess(result: T) = def.resolve(result)
    })
    return def.promise
}

operator fun SimpleLongProperty.plusAssign(l: Long) = this.set(this.get() + l)
operator fun SimpleLongProperty.minusAssign(l: Long) = this.set(this.get() - l)

operator fun Coin.minus(coin: Coin) = this.subtract(coin)