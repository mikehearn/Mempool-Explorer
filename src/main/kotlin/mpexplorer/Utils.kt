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

fun later<T>(): T = null as T

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

fun <V, E> Promise<Promise<V, E>, E>.unwrap(): Promise<V, E> {
    val deferred = deferred<V, E>()
    this.success {
        it.success {
           deferred.resolve(it)
        } fail {
            deferred.reject(it)
        }
    } fail {
        deferred.reject(it)
    }
    return deferred.promise
}

fun <T> ListenableFuture<T>.toPromise(): Promise<T, Exception> {
    val def = deferred<T, Exception>()
    Futures.addCallback(this, object : FutureCallback<T> {
        override fun onFailure(t: Throwable) = def.reject(t as Exception)
        override fun onSuccess(result: T) = def.resolve(result)
    })
    return def.promise
}

fun SimpleLongProperty.plusAssign(l: Long) = this.set(this.get() + l)
fun SimpleLongProperty.minusAssign(l: Long) = this.set(this.get() - l)

fun Coin.minus(coin: Coin) = this.subtract(coin)