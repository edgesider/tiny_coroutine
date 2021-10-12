import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.fixedRateTimer

/**
 * 协程表达的是过程的相续，过程被组织成一张图，因而可以通过P->P的映射来描述
 */
class Runner {
    fun submit(f: () -> Unit) {}
    fun submitDelay(delayMillis: Long, f: () -> Unit) {}
//    fun runAfter(f: () -> Unit) {}
}

fun main() {
    val executor = Executors.newSingleThreadExecutor()
    val p0 = P(executor).starter {
        seq(
            func {
                println(Thread.currentThread().name)
                println("hello")
            },
            delay(1000),
            func {
                println(Thread.currentThread().name)
                println("hello")
            },
            delay(1000),
            func {
                println(Thread.currentThread().name)
                println("hello again")
            },
            loop({ true },
                delay(1000),
                func {
                    println(Thread.currentThread().name)
                    println("hello again")
                }
            )
        )
    }
//    val p1 = P.build {
//        seq(
//            delay(1000),
//            func { p0.onKill() })
//    }
//    p1.onThrow = { throw it }
//    p1.invoke()
    p0.invokeBlocking()
    executor.shutdown()
}

@Suppress("MemberVisibilityCanBePrivate")
class P(val executor: ExecutorService? = null) {
    fun seq(vararg psArr: BaseP): BaseP = SequenceP(psArr.toList())
    fun seq(ps: List<BaseP>): BaseP = SequenceP(ps)

    fun delay(delayMillis: Long): BaseP = this.suspend() { onDone, _ ->
        fixedRateTimer(initialDelay = delayMillis, period = 1000, daemon = true) {
            cancel()
            executor?.submit(onDone)
                ?: onDone()
        }
    }

    fun func(f: () -> Unit) = FuncP(f)

    fun closure(f: () -> BaseP) = ClosureP(f)

    fun loop(predicate: () -> Boolean, p: BaseP) = WhileP(predicate, p)
    fun loop(predicate: () -> Boolean, vararg ps: BaseP) = WhileP(predicate, seq(ps.toList()))

    fun suspend(handler: (onDone: OnDone, onThrow: OnThrow) -> Unit): BaseP =
        object : BaseP() {
            override var onKill: OnKill = {
                onThrow(KilledException())
            }

            override fun invoke() {
                handler(onDone, onThrow)
            }
        }

    fun starter(builder: P.() -> BaseP): BaseP {
        val p = builder.invoke(this)
        return object : BaseP() {
            override fun invoke() {
                p.onDone = onDone
                p.onThrow = onThrow
                onKill = p.onKill
                executor?.submit { p.invoke() }
                    ?: p.invoke()
            }
        }
    }
}
