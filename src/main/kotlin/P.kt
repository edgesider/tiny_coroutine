import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.fixedRateTimer

val EmptyP = FuncP {}

class KilledException : Throwable()

typealias OnDone = () -> Unit
typealias OnThrow = (t: Throwable) -> Unit
typealias OnKill = () -> Unit

abstract class BaseP {
    var onDone: OnDone = {}
    var onThrow: OnThrow = {}

    // TODO 判断是否正在运行
    open var onKill: OnKill = { onThrow(KilledException()) }
    abstract fun invoke()
}

class DelayP(private val delayMillis: Long) : BaseP() {
    override var onKill = {
        timer.cancel()
        onThrow(KilledException())
    }

    private lateinit var timer: Timer

    override fun invoke() {
        timer = fixedRateTimer(initialDelay = delayMillis, period = delayMillis, daemon = true) {
            timer.cancel()
            onDone()
        }
    }
}

/**
 * 只有sequenceP包含多个相续过程P，因此只需要在这里进行线程切换（续体拦截）
 */
class SequenceP(private val ps: List<BaseP>) : BaseP() {
    constructor(vararg psArr: BaseP) : this(psArr.toList())

    private var killed = false
    private var invoking = 0

    override var onKill = {
        killed = true
        ps.getOrNull(invoking)?.onKill?.invoke()
        Unit
    }

    override fun invoke() {
        if (ps.isEmpty()) {
            onDone()
            return
        }
        invoking = 0
        val done = done@{
            if (killed) {
                return@done
            }
            invoking++
            if (invoking < ps.size) {
                ps[invoking].invoke()
            } else {
                onDone()
            }
        }
        ps.forEach {
            it.onDone = done
            it.onThrow = onThrow
        }
        ps[0].invoke()
    }
}

class ParallelP(private val ps: List<BaseP>) : BaseP() {
    constructor(vararg psArr: BaseP) : this(psArr.toList())

    override var onKill = { ps.forEach { p -> p.onKill() } }

    override fun invoke() {
        if (ps.isEmpty()) {
            onDone()
            return
        }
        val len = ps.size
        var doneNum = 0
        val done = {
            doneNum++
            if (doneNum >= len) {
                onDone()
            }
        }
        ps.forEach { p ->
            p.onDone = done
            p.onThrow = onThrow
        }
        ps.forEach { p -> p.invoke() }
    }
}

class FuncP(private val func: () -> Unit) : BaseP() {
    override var onKill = {}

    override fun invoke() {
        try {
            func()
        } catch (ex: Throwable) {
            onThrow(ex)
            return
        }
        onDone()
    }
}

class ClosureP(private val func: () -> BaseP) : BaseP() {
    private var invoking: BaseP? = null

    override var onKill = {
        invoking?.onKill?.invoke()
        Unit
    }

    override fun invoke() {
        val p = func()
        invoking = p
        p.onDone = onDone
        p.onThrow = onThrow
        p.invoke()
    }
}

class WhileP(private val predicate: () -> Boolean, private val p: BaseP) : BaseP() {
    constructor(predicate: () -> Boolean, vararg psArr: BaseP) :
            this(predicate, SequenceP(psArr.toList()))

    private var killed = false

    override var onKill = {
        killed = true
        p.onKill()
    }

    override fun invoke() {
        val loop = {
            // TODO killed to onThrow
            if (killed || !predicate()) {
                // loop exit
                onDone()
            } else {
                p.invoke()
            }
        }
        p.onDone = loop
        p.onThrow = onThrow
        loop()
    }
}

class IfP(private val predicate: () -> Boolean, private val `if`: BaseP, private val `else`: BaseP = EmptyP) : BaseP() {
    override var onKill = {
        invoking!!.onKill.invoke()
    }

    private var invoking: BaseP? = null

    override fun invoke() {
        if (predicate()) {
            `if`.onDone = onDone
            `if`.onThrow = onThrow
            invoking = `if`
            `if`.invoke()
        } else {
            `else`.onDone = onDone
            `else`.onThrow = onThrow
            invoking = `else`
            `else`.invoke()
        }
    }
}

class PList<T>(private val list: MutableList<T> = mutableListOf()) : MutableList<T> by list {
    private val waiting = mutableListOf<OnDone>()

    fun waitAvailable(): ClosureP = ClosureP {
        if (size > 0) {
            // 立即完成
            EmptyP
        } else {
            object : BaseP() {
                override var onKill = {
                    onThrow(KilledException())
                }

                override fun invoke() {
                    waiting.add(onDone)
                }
            }
        }
    }

    override fun add(element: T): Boolean {
        val rv = list.add(element)
        waiting.getOrNull(0)?.let {
            waiting.removeAt(0).invoke()
        }
        return rv
    }
}

class PResult private constructor(val type: Result, val exception: Throwable? = null) {
    companion object {
        fun done() = PResult(Result.Done)
        fun killed(ex: KilledException) = PResult(Result.Killed, ex)
        fun exception(ex: Throwable) = PResult(Result.Exception, ex)
    }

    enum class Result {
        Done,
        Killed,
        Exception;
    }
}

fun BaseP.invokeBlocking() {
    val fut = CompletableFuture<PResult>()
    var done = false

    onDone = {
        done = true
        fut.complete(PResult.done())
    }
    onThrow = {
        done = true
        if (it is KilledException) {
            fut.complete(PResult.killed(it))
        } else {
            fut.complete(PResult.exception(it))
        }
    }
    invoke()

    if (done)
        return
    val res = fut.join()
    when (res.type) {
        PResult.Result.Done -> return
        PResult.Result.Killed -> throw res.exception!!
        PResult.Result.Exception -> throw res.exception!!
    }
}
